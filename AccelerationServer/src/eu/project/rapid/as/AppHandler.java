package eu.project.rapid.as;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OptionalDataException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
// import org.bouncycastle.jce.provider.BouncyCastleProvider;

import eu.project.rapid.ac.ResultContainer;
import eu.project.rapid.common.RapidMessages;
import eu.project.rapid.common.RapidUtils;
import eu.project.rapid.utils.Configuration;
import eu.project.rapid.utils.SharedLibDependencyGraph;
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
  private File appLibFolder;
  private String jarFilePath; // the path where the jar file is stored
  private int vmHelperId = 0;

  private final int BUFFER = 8192;
  private LinkedList<File> libraries;
  private LinkedList<File> librariesSorted; // Sorted in dependency order
  private Set<String> libNames;
  private SharedLibDependencyGraph dependencies;

  private static Map<String, RapidClassLoader> classLoaders;
  private RapidClassLoader rapidClassLoader;

  public AppHandler(Socket socket, Configuration config) {

    this.clientSocket = socket;
    this.config = config;
    libraries = new LinkedList<>();
    librariesSorted = new LinkedList<>();
    libNames = new HashSet<>();
    dependencies = new SharedLibDependencyGraph();

    if (classLoaders == null) {
      classLoaders = new HashMap<>();
    }

    startListening();

  }

  private void startListening() {
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

            rapidClassLoader = classLoaders.get(jarName);
            if (rapidClassLoader == null) {
              rapidClassLoader = new RapidClassLoader(appFolderPath);
            } else {
              rapidClassLoader.setAppFolder(appFolderPath);
            }
            classLoaders.put(jarName, rapidClassLoader);
            // dOis.setAppFolder(appFolderPath);
            dOis.setClassLoader(rapidClassLoader);

            addLibraries();
            calculateLibDependencies();

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
   * Extract native libraries for the x86 platform included in the .jar file (which is actually a
   * zip file).
   * 
   * The x86 shared libraries are: libs/library.so inside the jar file. They are extracted from the
   * jar and saved in appFolderPath/libs. Initially we used to save them with the same name as the
   * original (library.so) but this caused many problems related to classloaders. When an app was
   * offloaded for the first time and used the library, the library was loaded in the jvm. If the
   * client disconnected, the classloader that loaded the library was not unloaded, which means that
   * also the library was not unloaded from the jvm. On consequent offloads of the same app, the
   * classloader is different, meaning that the library could not be loaded anymore due to the fact
   * that was already loaded by another classloader. But it could not even be used, due to the fact
   * that the classloaders differ.<br>
   * <br>
   * To solve this problem we save the library within a new folder, increasing a sequence number
   * each time the same app is offloaded. So, the library.so file will be saved as
   * library-1/library.so, library-2/library.so, and so on.
   * 
   * @return the list of shared libraries
   */

  @SuppressWarnings("unchecked")
  private void addLibraries() {
    Long startTime = System.nanoTime();

    FilenameFilter libsFilter = new FilenameFilter() {
      public boolean accept(File dir, String name) {
        String lowercaseName = name.toLowerCase();
        if (lowercaseName.startsWith("libs")) {
          return true;
        } else {
          return false;
        }
      }
    };

    // Folder where the libraries are extracted
    File[] libsFolders = new File(appFolderPath).listFiles(libsFilter);
    if (libsFolders.length > 1) {
      log.warn("More than on libs folder is present, not clear how proceed now: ");
      for (File f : libsFolders) {
        log.info("\t" + f.getAbsolutePath());
      }
    } else if (libsFolders.length == 1) {
      appLibFolder = libsFolders[0];
      log.info("Found exactly one libs folder: " + appLibFolder.getAbsolutePath());
      if (appLibFolder.exists() && appLibFolder.isDirectory()) {
        int currVersion = 1;
        if (!appLibFolder.getName().equals("libs")) {
          currVersion = Integer.parseInt(appLibFolder.getName().substring("libs-".length())) + 1;
        }
        appLibFolder
            .renameTo(new File(appLibFolder.getParent() + File.separator + "libs-" + currVersion));
        appLibFolder = new File(appLibFolder.getParent() + File.separator + "libs-" + currVersion);

        log.info("Renaming folder to: " + appLibFolder.getParent() + File.separator + "libs-"
            + currVersion);

        for (File f : appLibFolder.listFiles()) {
          if (Utils.isLinux() && f.getName().contains(".so")
              || (Utils.isMac() && f.getName().contains(".jnilib"))) {
            // Store the library to the list
            libraries.add(f);
            libNames.add(f.getName());

            // Copy the file on the rapid system lib folder
            File newLibFile = new File(
                config.getRapidFolder() + File.separator + "libs" + File.separator + f.getName());
            log.info("Copying file " + f + " to " + newLibFile + "...");
            try {
              Files.copy(f.toPath(), newLibFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
              log.error("Error while copying file " + f + " to " + newLibFile + ": " + e);
            }
          }
        }
      }
    } else {
      log.info("No libs* folder present, no shared libraries to load.");
    }

    log.info(
        "Duration of creating libraries: " + ((System.nanoTime() - startTime) / 1000000) + "ms");
  }

  private void calculateLibDependencies() {
    for (File currLibFile : libraries) {
      String result = RapidUtils
          .executeCommand("objdump -x " + currLibFile.getAbsolutePath() + " | grep NEEDED");

      // System.out.println(result);
      String[] splitResult = result.split("\\s+");

      System.out.println("Dependencies of library: " + currLibFile.getName());
      dependencies.addLibrary(currLibFile.getName());
      for (String tempLib : splitResult) {
        if (tempLib.contains(".so")) {
          if (libNames.contains(tempLib)) {
            dependencies.addDependency(currLibFile.getName(), tempLib);
            System.out.println(tempLib);
          }
        }
      }
    }

    // Insert the libraries in a sorted list where the non dependent ones are put first.
    log.info("List of libraries sorted based on their dependencies:");
    for (int i = 0; i < libNames.size(); i++) {
      String temp = dependencies.getOneNonDependentLib();
      if (temp != null) {
        log.info(temp);
        librariesSorted.add(new File(appLibFolder + File.separator + temp));
      }
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
      Constructor<?> cons = dfeType.getDeclaredConstructor(boolean.class);
      // Constructor<?> cons = dfeType.getConstructor(boolean.class);
      Object dfe = null;
      try {
        cons.setAccessible(true);
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
        // long s = System.nanoTime();
        Method prepareDataMethod = objToExecute.getClass().getDeclaredMethod("prepareDataOnServer");
        prepareDataMethod.setAccessible(true);
        // RapidUtils.sendAnimationMsg(config, RapidMessages.AC_PREPARE_DATA);
        long s = System.nanoTime();
        prepareDataMethod.invoke(objToExecute);
        long prepareDataDuration = System.nanoTime() - s;
        log.warn(
            "Executed method prepareDataOnServer() on " + (prepareDataDuration / 1000000) + " ms");

      } catch (NoSuchMethodException e) {
        log.warn("The method prepareDataOnServer() does not exist");
      }

      try {
        log.info("1. List of already loaded libraries: ");
        listAllLoadedNativeLibrariesFromJVM();
        result = runMethod.invoke(objToExecute, pValues);
      } catch (InvocationTargetException e) {
        e.printStackTrace();
        // The method might have failed if the required shared library
        // had not been loaded before, try loading the jar's libraries and
        // restarting the method
        if (e.getTargetException() instanceof UnsatisfiedLinkError
            || e.getTargetException() instanceof ExceptionInInitializerError) {
          log.error(
              "UnsatisfiedLinkError thrown, loading libs from" + appLibFolder + " and retrying");

          log.info("Current classloader: " + AppHandler.class.getClassLoader());
          log.info("System classloader: " + ClassLoader.getSystemClassLoader());
          log.info("Object classloader: " + objToExecute.getClass().getClassLoader());
          log.info("dOis classloader: " + dOis.getClassLoader());

          Method libLoader = objClass.getMethod("loadLibraries", LinkedList.class);
          try {
            libLoader.invoke(objToExecute, librariesSorted);

            log.info("2. List of already loaded libraries: ");
            listAllLoadedNativeLibrariesFromJVM();

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

      // If this is the main VM send back also the object to execute,
      // otherwise the helper VMs don't need to send it back.
      if (vmHelperId == 0) {
        // If we choose to also send back the object then the number of bytes will increase.
        // If necessary just uncomment the line below.
        return new ResultContainer(objToExecute, result, getObjectDuration, execDuration);
        // return new ResultContainer(null, result, getObjectDuration, execDuration);
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

  private void listAllLoadedNativeLibrariesFromJVM() {
    ClassLoader appLoader = ClassLoader.getSystemClassLoader();
    ClassLoader currentLoader = AppHandler.class.getClassLoader();
    ClassLoader objLoader = objToExecute.getClass().getClassLoader();
    ClassLoader dOisLoader = dOis.getClassLoader();

    // ClassLoader[] loaders = new ClassLoader[] {appLoader, currentLoader, objLoader};
    ClassLoader[] loaders = new ClassLoader[] {objLoader};
    final String[] libraries = ClassScope.getLoadedLibraries(loaders);
    for (String library : libraries) {
      System.out.println(library);
    }
  }
}
