package eu.project.rapid.as;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OptionalDataException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
// import org.bouncycastle.jce.provider.BouncyCastleProvider;

import eu.project.rapid.ac.ResultContainer;
import eu.project.rapid.common.RapidMessages;
import eu.project.rapid.common.RapidUtils;
import eu.project.rapid.utils.Configuration;
import eu.project.rapid.utils.Utils;

public class AppHandler {
  //
  // static {
  // Security.insertProviderAt(new BouncyCastleProvider(), 1);
  // }

  private final Logger log = LogManager.getLogger(AppHandler.class.getSimpleName());
  private Configuration config;

  private Socket clientSocket;
  private InputStream is;
  private OutputStream os;
  private DynamicObjectInputStream dOis;
  private ObjectOutputStream oos;

  // Variables related to method execution
  private Object objToExecute;
  private int nrVMs;
  private String methodName;
  private String jarName; // the app name sent by the phone
  private long jarLength; // the app length in bytes sent by the phone
  private Class<?>[] pTypes; // the types of the parameters passed to the method
  private Object[] pValues; // the values of the parameters to be passed to the method
  private Class<?> returnType; // the return type of the method
  private String appFolderPath; // the path where the files for this app are stored
  private String jarFilePath; // the path where the jar file is stored
  private int vmHelperId = 0;


  public AppHandler(Socket socket, Configuration config) {

    this.clientSocket = socket;
    this.config = config;

    try {
      is = clientSocket.getInputStream();
      os = clientSocket.getOutputStream();
      dOis = new DynamicObjectInputStream(is);
      oos = new ObjectOutputStream(os);

      int command = 0;
      do {
        command = is.read();
        log.info("Received command: " + command);
        switch (command) {
          case RapidMessages.PING:
            os.write(RapidMessages.PING);
            break;

          case RapidMessages.AC_REGISTER_AS:
            log.info("Client requesting REGISTER");
            jarName = dOis.readUTF();
            jarLength = dOis.readLong();
            appFolderPath = this.config.getRapidFolder() + File.separator + jarName;
            jarFilePath = appFolderPath + File.separator + jarName + ".jar";
            if (!appExists()) {
              log.info("Jar file not present or old version, should read the jar file");
              os.write(RapidMessages.AS_APP_REQ_AC);
              receiveJarFile();
            } else {
              log.info("Jar file already present");
              os.write(RapidMessages.AS_APP_PRESENT_AC);
            }
            dOis.setJar(appFolderPath, jarFilePath);
            break;

          case RapidMessages.AC_OFFLOAD_REQ_AS:
            log.info("Client requesting OFFLOAD_EXECUTION");
            Object result = retrieveAndExecute();
            oos.writeObject(result);
            oos.flush();
            oos.reset();
            break;
        }
      } while (command != -1);

    } catch (IOException e) {
      log.error("Could not create client streams: " + e);
      e.printStackTrace();
    } finally {
      RapidUtils.closeQuietly(oos);
      RapidUtils.closeQuietly(dOis);
      RapidUtils.closeQuietly(clientSocket);
    }
  }

  /**
   * Reads in the object to execute an operation on, name of the method to be executed and executes
   * it
   * 
   * @param objIn Dynamic object input stream for reading an arbitrary object (class loaded from a
   *        previously obtained dex file inside an apk)
   * @return result of executing the required method
   * @throws IllegalArgumentException
   * @throws IllegalAccessException
   * @throws InvocationTargetException
   * @throws OptionalDataException
   * @throws ClassNotFoundException
   * @throws IOException
   * @throws SecurityException
   * @throws NoSuchMethodException
   * @throws NoSuchFieldException
   */
  private Object retrieveAndExecute() {
    long getObjectDuration = -1;
    long startTime = System.nanoTime();

    // Read the object in for execution
    log.info("Read Object");
    try {

      // Receive the number of VMs needed
      nrVMs = dOis.readInt();
      log.info("The user is asking for " + nrVMs + " VMs");
      // numberOfCloneHelpers--;
      // withMultipleClones = numberOfCloneHelpers > 0;

      // Get the object
      objToExecute = dOis.readObject();

      // Get the class of the object, dynamically
      Class<?> objClass = objToExecute.getClass();

      getObjectDuration = System.nanoTime() - startTime;

      log.info("Done Reading Object: " + objClass.getName() + " in "
          + (getObjectDuration / 1000000.0) + " ms");

      // Set up server-side DFE for the object
      java.lang.reflect.Field asDFE = objClass.getDeclaredField("dfe");
      asDFE.setAccessible(true);

      Class<?> dfeType = asDFE.getType();
      Constructor<?> cons = dfeType.getConstructor(boolean.class);
      Object dfe = null;
      try {
        dfe = cons.newInstance(true);
      } catch (InstantiationException e) {
        // too bad. still try to carry on.
        log.error("Could not instantiate the server-side DFE: " + e);
        // e.printStackTrace();
      }
      asDFE.set(objToExecute, dfe);

      log.info("Read Method");
      // Read the name of the method to be executed
      methodName = (String) dOis.readObject();

      Object tempTypes = dOis.readObject();
      pTypes = (Class[]) tempTypes;

      Object tempValues = dOis.readObject();
      pValues = (Object[]) tempValues;

      log.info("Run Method " + methodName);
      // Get the method to be run by reflection
      Method runMethod = objClass.getDeclaredMethod(methodName, pTypes);
      // And force it to be accessible (quite often will be declared private originally)
      runMethod.setAccessible(true);

      // if (withMultipleClones) {
      // pausedHelper = new Boolean[numberOfCloneHelpers + 1];
      // for (int i = 1; i < numberOfCloneHelpers + 1; i++)
      // pausedHelper[i] = true;
      //
      // withMultipleClones = connectToServerHelpers();
      //
      // if (withMultipleClones) {
      // Log.i(TAG, "The clones are successfully allocated.");
      //
      // returnType = runMethod.getReturnType(); // the return type of the offloaded method
      //
      // // Allocate the space for the responses from the other clones
      // responsesFromServers = Array.newInstance(returnType, numberOfCloneHelpers + 1);
      //
      // // Wait until all the threads are connected to the clone helpers
      // waitForThreadsToBeReady();
      //
      // Log.d(Constants.DEMO_TAG, "DISTRIBUTE_EXECUTION");
      //
      // // Give the command to register the app first
      // sendCommandToAllThreads(ControlMessages.APK_REGISTER);
      //
      // // Wait again for the threads to be ready
      // waitForThreadsToBeReady();
      //
      // // And send a ping to all clones just for testing
      // // sendCommandToAllThreads(ControlMessages.PING);
      // // waitForThreadsToBeReady();
      // /**
      // * Wake up the server helper threads and tell them to send the object to execute, the
      // * method, parameter types and parameter values
      // */
      // sendCommandToAllThreads(ControlMessages.EXECUTE);
      // } else {
      // Log.i(TAG, "Could not allocate other clones, doing only my part of the job.");
      // }
      // }

      // Run the method and retrieve the result
      Object result;
      Long execDuration = null;
      Long startExecTime = System.nanoTime();
      try {
        result = runMethod.invoke(objToExecute, pValues);
      } catch (InvocationTargetException e) {
        // The method might have failed if the required shared library
        // had not been loaded before, try loading the jar's libraries and
        // restarting the method
        if (e.getTargetException() instanceof UnsatisfiedLinkError
            || e.getTargetException() instanceof ExceptionInInitializerError) {
          log.error("UnsatisfiedLinkError thrown, loading libs and retrying");

          Method libLoader = objClass.getMethod("loadLibraries", LinkedList.class);
          try {
            // libLoader.invoke(objToExecute, libraries);
            result = runMethod.invoke(objToExecute, pValues);
          } catch (InvocationTargetException e1) {
            log.error("InvocationTargetException after loading the libraries");
            result = e1;
            e1.printStackTrace();
          }
        } else {
          log.error("The remote execution resulted in exception:  " + e);
          result = e;
          e.printStackTrace();
        }
      } finally {
        execDuration = System.nanoTime() - startExecTime;
        log.info(
            runMethod.getName() + ": pure execution time - " + (execDuration / 1000000) + "ms");
      }

      log.info(runMethod.getName() + ": retrieveAndExecute time - "
          + ((System.nanoTime() - startTime) / 1000000) + "ms");
      //
      // if (withMultipleClones) {
      // // Wait for all the clones to finish execution before returning the result
      // waitForThreadsToBeReady();
      // Log.d(TAG, "All servers finished execution, send result back.");
      //
      // // Kill the threads.
      // sendCommandToAllThreads(-1);
      //
      // synchronized (responsesFromServers) {
      // Array.set(responsesFromServers, 0, result); // put the result of the main clone as the
      // // first element of the array
      // }
      //
      // // Call the reduce function implemented by the developer to combine the partial results.
      // try {
      // // Array of the returned type
      // Class<?> arrayReturnType =
      // Array.newInstance(returnType, numberOfCloneHelpers + 1).getClass();
      // Method runMethodReduce =
      // objClass.getDeclaredMethod(methodName + "Reduce", arrayReturnType);
      // runMethodReduce.setAccessible(true);
      // Log.i(TAG, "Reducing the results using the method: " + runMethodReduce.getName());
      //
      // Object reducedResult =
      // runMethodReduce.invoke(objToExecute, new Object[] {responsesFromServers});
      // result = reducedResult;
      //
      // Log.i(TAG, "The reduced result: " + reducedResult);
      //
      // } catch (Exception e) {
      // Log.e(TAG, "Impossible to reduce the result");
      // e.printStackTrace();
      // }
      // }

      objToExecute = null;

      // If this is the main VM send back also the object to execute,
      // otherwise the helper VMs don't need to send it back.
      if (vmHelperId == 0) {
        // If we choose to also send back the object then the number of bytes will increase.
        // If necessary just uncomment the line below.
        // return new ResultContainer(objToExecute, result, execDuration);
        return new ResultContainer(null, result, getObjectDuration, execDuration);
      } else {
        return new ResultContainer(null, result, getObjectDuration, execDuration);
      }

    } catch (Exception e) {
      // Catch and return any exception since we do not know how to handle
      // them on the server side
      log.error("Generic exception happened while executing remotely: " + e);
      e.printStackTrace();
      return new ResultContainer(e, getObjectDuration);
    }

  }


  /**
   * TODO check if the app already exists on the AS
   * 
   * @param jarName
   * @param jarSize
   * @return
   */
  private boolean appExists() {
    log.info("Checking if the jar file '" + jarFilePath + "' with size " + jarLength + " exists");
    File jarFile = new File(jarFilePath);
    if (jarFile.exists() && jarFile.length() == jarLength) {
      return true;
    }
    return false;
  }

  /**
   * Receive the jar file from the client and extract its content on the appropriate folder.
   */
  private void receiveJarFile() {
    log.info("Receiving the jar file...");
    File appFolder = new File(appFolderPath);
    if (appFolder.exists()) {
      // Delete all files inside the directory.
      Utils.deleteDir(appFolder);
    }
    // Create the folder.
    appFolder.mkdirs();

    File jarFile = new File(appFolder, jarName + ".jar");
    FileOutputStream fos = null;
    try {
      fos = new FileOutputStream(jarFile);
      byte[] buffer = new byte[4096];
      int totalRead = 0;
      long remaining = jarLength;
      int read = 0;
      while ((read = is.read(buffer, 0, (int) Math.min(buffer.length, remaining))) > 0) {
        fos.write(buffer, 0, read);
        totalRead += read;
        remaining -= read;
      }

      log.info("Succesfully read the " + totalRead + " bytes of the jar file, extracting now.");
      extractJarFile(appFolder, jarFile);

      // The client is waiting for an ACK that the jar file was correctly received and extracted.
      os.write(1);
    } catch (FileNotFoundException e) {
      log.error("Could not create jar file: " + e);
    } catch (IOException e) {
      log.error("Error while receiving jar file: " + e);
    } finally {
      if (fos != null) {
        try {
          fos.close();
        } catch (IOException e) {
          log.error("Error while closing fos of jar file: " + e);
        }
      }
    }
  }

  private void extractJarFile(File appFolder, File jarFile) {
    try (JarFile jar = new JarFile(jarFile)) {
      Enumeration<JarEntry> enumeration = jar.entries();
      while (enumeration.hasMoreElements()) {
        JarEntry jarEntry = enumeration.nextElement();
        File f = new File(appFolder, jarEntry.getName());
        if (jarEntry.isDirectory()) {
          f.mkdirs();
        } else {
          if (!f.getParentFile().exists()) {
            f.getParentFile().mkdirs();
          }
          InputStream is = jar.getInputStream(jarEntry);
          FileOutputStream fos2 = new FileOutputStream(f);
          byte[] b = new byte[4096];
          while (is.available() > 0) {
            int read = is.read(b);
            fos2.write(b, 0, read);
            // fos2.write(is.read());
          }
          fos2.close();
          is.close();
        }
      }
      log.info("Finished extracting the jar file.");
    } catch (IOException e) {
      log.error("Error while extracting the jar file: " + e);
      e.printStackTrace();
    }
  }
}
