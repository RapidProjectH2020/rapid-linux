package eu.project.rapid.as;

import eu.project.rapid.ac.ResultContainer;
import eu.project.rapid.common.Clone;
import eu.project.rapid.common.RapidMessages;
import eu.project.rapid.common.RapidUtils;
import eu.project.rapid.utils.Configuration;
import eu.project.rapid.utils.SharedLibDependencyGraph;
import eu.project.rapid.utils.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * This class deals with the clients that connect for computation offloading.
 * Currently it handles correctly the normal Java methods.
 * The native libraries are quite tricky. For now I am reusing the same classloader here
 * (implemented as static) for the same application. This allows for proper execution of native functions,
 * but it presents problems if the native library clientIs modified. If that happens, the current classloader will not
 * load the new native library, which means that the offloaded native methods will not be able to run the latest
 * implementation.
 * Also the Java methods will have the same issue, since a class cannot be loaded twice on the same classloader.
 * By having the <code>classLoaders</code> variable as non static, we solve the issue of the Java classes
 * (since the classloader will be a new one every time the client connects)
 * but we have the problem of the native libraries.
 * <p>
 * FIXME: try to find a solution for this, similar to the Android one.
 * <p>
 * Currently, launching the AS as following:
 * java -Djava.library.path=~/rapid-server/libs/ -jar rapid-linux-as.jar
 */
public class AppHandler implements Runnable {

    private static AtomicInteger counter = new AtomicInteger(0);
    private final int id = counter.getAndIncrement();
    private final Logger log = LogManager.getLogger(AppHandler.class.getSimpleName() + "-" + id);
    private Configuration config;

    private Socket clientSocket;
    private InputStream clientIs;
    private OutputStream clientOs;
    private DynamicObjectInputStream clientDOis;
    private ObjectOutputStream clientOos;

    // Variables related to method execution
    private Object objToExecute;
    private String jarName; // the app name sent by the phone
    private long jarLength; // the app length in bytes sent by the phone
    private String appFolderPath; // the path where the files for this app are stored
    private File appLibFolder;
    private String jarFilePath; // the path where the jar file clientIs stored

    private static Map<String, Integer> apkMap = new ConcurrentHashMap<>(); // appName, apkSize
    private static Map<String, CountDownLatch> apkMapSemaphore = new ConcurrentHashMap<>(); // appName, latch
    private static final Object syncAppExistsObject = new Object();
    private static final Object syncRegistrationObject = new Object();
    private static final Object syncLibrariesExtractObject = new Object();

    private LinkedList<File> libraries;
    private LinkedList<File> librariesSorted; // Sorted in dependency order
    private Set<String> libNames;
    private SharedLibDependencyGraph dependencies;

    private static Map<String, RapidClassLoader> classLoaders;

    // The number of clone helpers requested (not considering the main VM)
    private static int numberOfCloneHelpers = 0;
    private Boolean[] pausedHelper; // Needed for synchronization with the clone helpers
    private int requestFromMainServer = 0; // The main clone sends commands to the clone helpers
    private Object responsesFromServers; // Array of partial results returned by the clone helpers
    private final AtomicInteger nrClonesReady = new AtomicInteger(0); // The main thread waits for all the
    // clone helpers to finish execution
    private String methodName; // the method to be executed
    private Class<?>[] pTypes; // the types of the parameters passed to the method
    private Object[] pValues; // the values of the parameters to be passed to the method
    private Class<?> returnType; // the return type of the method
    // The main thread has cloneId = 0
    // the clone helpers have cloneId \in [1, nrClones-1]
    private int cloneHelperId = 0;

    // I need this variable for when the DS starts the VM migration.
    // The AS will let the DS shut down the VM only if this clientIs equal to 0.
    private static final AtomicInteger nrTasksCurrentlyBeingExecuted = new AtomicInteger(0);
    private static AtomicBoolean migrationInProgress = new AtomicBoolean(false);

    AppHandler(Socket socket, Configuration config) {

        this.clientSocket = socket;
        this.config = config;
        libraries = new LinkedList<>();
        librariesSorted = new LinkedList<>();
        libNames = new HashSet<>();
        dependencies = new SharedLibDependencyGraph();

        if (classLoaders == null) {
            classLoaders = new ConcurrentHashMap<>();
        }
    }

    @Override
    public void run() {
        try {
            clientIs = clientSocket.getInputStream();
            clientOs = clientSocket.getOutputStream();
            clientDOis = new DynamicObjectInputStream(clientIs);
            clientOos = new ObjectOutputStream(clientOs);

            int command;
            do {
                command = clientIs.read();
                log.info("Received command: " + command);
                switch (command) {
                    case RapidMessages.PING:
                        clientOs.write(RapidMessages.PING);
                        break;

                    case RapidMessages.AC_REGISTER_AS:
                        log.info("Client requesting REGISTER");
                        jarName = clientDOis.readUTF();
                        jarLength = clientDOis.readLong();
                        appFolderPath = this.config.getRapidFolder() + File.separator + jarName;
                        jarFilePath = appFolderPath + File.separator + jarName + ".jar";

                        synchronized (syncRegistrationObject) {
                            log.info("Taking the RapidClassloader for application: " + jarName);
                            RapidClassLoader rapidClassLoader = classLoaders.get(jarName);
                            if (rapidClassLoader == null) {
                                rapidClassLoader = new RapidClassLoader(appFolderPath);
                                log.info("Created new RapidClassloader for application: " + jarName + ", " + rapidClassLoader);
                            } else {
                                rapidClassLoader.setAppFolder(appFolderPath);
                                log.info("Updated the RapidClassloader for application: " + jarName + ", " + rapidClassLoader);
                            }
                            classLoaders.put(jarName, rapidClassLoader);
                            clientDOis.setClassLoader(rapidClassLoader);
                        }

                        if (appExists()) {
                            log.info("Jar file already present");
                            clientOs.write(RapidMessages.AS_APP_PRESENT_AC);
                        } else {
                            log.info("Jar file not present or old version, should read the jar file");
                            clientOs.write(RapidMessages.AS_APP_REQ_AC);
                            receiveJarFile();
                            apkMapSemaphore.get(jarName).countDown();
                        }

                        // Wait for the file to be written on the disk
                        try {
                            apkMapSemaphore.get(jarName).await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }

                        addLibraries();
                        calculateLibDependencies();

                        break;

                    case RapidMessages.AC_OFFLOAD_REQ_AS:
                        log.info("Client requesting OFFLOAD_EXECUTION");
                        synchronized (nrTasksCurrentlyBeingExecuted) {
                            if (migrationInProgress.get()) {
                                log.info("VM upgrade in progress, cannot accept new tasks");
                                // Simply closing the connection will force the client to run tasks locally
                                closeConnection();
                                break;
                            }

                            nrTasksCurrentlyBeingExecuted.incrementAndGet();
                            log.info("The new task clientIs accepted for execution, total nr of tasks: "
                                    + nrTasksCurrentlyBeingExecuted.get());
                        }

                        try {
                            Object result = retrieveAndExecute();
                            clientOos.writeObject(result);
                            clientOos.flush();
                            clientOos.reset();
                        } catch (IOException e) {
                            log.error("Could not send the result back to the client: " + e);
                        } finally {
                            synchronized (nrTasksCurrentlyBeingExecuted) {
                                nrTasksCurrentlyBeingExecuted.decrementAndGet();
                                nrTasksCurrentlyBeingExecuted.notifyAll();
                            }
                        }

                        break;

                    case RapidMessages.CLONE_ID_SEND:
                        cloneHelperId = clientIs.read();
                        Utils.writeCloneHelperId(cloneHelperId);
                        break;

                    case RapidMessages.DS_MIGRATION_VM_AS:
                        // When the QoS are not respected, the VM will be upgraded.
                        // In that case, the DS informs the AS, so that the AS can inform the AC.
                        long userId = clientDOis.readLong();
                        log.info("The VMM is informing that there will be a VM migration/update, user id = " + userId);
                        migrationInProgress.getAndSet(true);

                        synchronized (nrTasksCurrentlyBeingExecuted) {
                            while (nrTasksCurrentlyBeingExecuted.get() > 0) {
                                try {
                                    nrTasksCurrentlyBeingExecuted.wait();
                                } catch (Exception e) {
                                    log.error("Exception while waiting for no more tasks in execution: " + e);
                                }
                            }
                        }
                        log.info("No more tasks under execution, informing the VMM that the upgrade can go on...");
                        clientOos.writeByte(RapidMessages.OK);
                        clientOos.flush();
                        break;
                }
            } while (command != -1);

        } catch (IOException e) {
            log.warn("Client disconnected: " + e);
        } finally {
            closeConnection();
        }
    }

    private void closeConnection() {
        RapidUtils.closeQuietly(clientOos);
        RapidUtils.closeQuietly(clientDOis);
        RapidUtils.closeQuietly(clientSocket);
    }

    /**
     * Extract native libraries for the x86 platform included in the .jar file (which clientIs actually a
     * zip file).
     * <p>
     * The x86 shared libraries are: libs/library.so inside the jar file. They are extracted from the
     * jar and saved in appFolderPath/libs. Initially we used to save them with the same name as the
     * original (library.so) but this caused many problems related to classloaders. When an app was
     * offloaded for the first time and used the library, the library was loaded in the jvm. If the
     * client disconnected, the classloader that loaded the library was not unloaded, which means that
     * also the library was not unloaded from the jvm. On consequent offloads of the same app, the
     * classloader clientIs different, meaning that the library could not be loaded anymore due to the fact
     * that was already loaded by another classloader. But it could not even be used, due to the fact
     * that the classloaders differ.<br>
     * <br>
     * To solve this problem we save the library within a new folder, increasing a sequence number
     * each time the same app clientIs offloaded. So, the library.so file will be saved as
     * library-1/library.so, library-2/library.so, and so on.
     */

    @SuppressWarnings("unchecked")
    private void addLibraries() {
        synchronized (syncLibrariesExtractObject) {

            log.info("Extracting native libraries...");
            Long startTime = System.nanoTime();

            FilenameFilter libsFilter = (dir, name) -> {
                String lowercaseName = name.toLowerCase();
                return lowercaseName.startsWith("libs");
            };

            // Folder where the libraries are extracted
            File[] libsFolders = new File(appFolderPath).listFiles(libsFilter);
            if (libsFolders != null && libsFolders.length > 1) {
                log.warn("More than one libs folder client is present, not clear how proceed now: ");
                for (File f : libsFolders) {
                    log.info("\t" + f.getAbsolutePath());
                }
            } else if (libsFolders != null && libsFolders.length == 1) {
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

            log.info("Duration of creating libraries: " + ((System.nanoTime() - startTime) / 1000000) + "ms");
        }
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
     * @return result of executing the required method
     * @throws IllegalArgumentException
     * @throws SecurityException
     */
    private Object retrieveAndExecute() {
        long getObjectDuration = -1;
        long startTime = System.nanoTime();

        // Read the object in for execution
        log.info("Read Object");
        try {

            // Receive the number of VMs needed
            int nrVMs = clientDOis.readInt();
            log.info("The user clientIs asking for " + nrVMs + " VMs");
            numberOfCloneHelpers--;
            boolean withMultipleClones = numberOfCloneHelpers > 0;

            // Get the object
            objToExecute = clientDOis.readObject();

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
            cons.setAccessible(true);
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
            methodName = (String) clientDOis.readObject();

            Object tempTypes = clientDOis.readObject();
            pTypes = (Class[]) tempTypes;

            Object tempValues = clientDOis.readObject();
            pValues = (Object[]) tempValues;

            log.info("Run Method " + methodName);
            // Get the method to be run by reflection
            Method runMethod = objClass.getDeclaredMethod(methodName, pTypes);
            // And force it to be accessible (quite often will be declared private originally)
            runMethod.setAccessible(true);

            if (withMultipleClones) {
                pausedHelper = new Boolean[numberOfCloneHelpers + 1];
                for (int i = 1; i < numberOfCloneHelpers + 1; i++)
                    pausedHelper[i] = true;

                withMultipleClones = connectToServerHelpers();

                if (withMultipleClones) {
                    log.info("The clones are successfully allocated.");

                    returnType = runMethod.getReturnType(); // the return type of the offloaded method

                    // Allocate the space for the responses from the other clones
                    responsesFromServers = Array.newInstance(returnType, numberOfCloneHelpers + 1);

                    // Wait until all the threads are connected to the clone helpers
                    waitForThreadsToBeReady();

                    // Give the command to register the app first
                    sendCommandToAllThreads(RapidMessages.AC_REGISTER_AS);

                    // Wait again for the threads to be ready
                    waitForThreadsToBeReady();

                    // And send a ping to all clones just for testing
                    // sendCommandToAllThreads(ControlMessages.PING);
                    // waitForThreadsToBeReady();

                    // Wake up the server helper threads and tell them to send the object to execute, the
                    // method, parameter types and parameter values
                    sendCommandToAllThreads(RapidMessages.AC_OFFLOAD_REQ_AS);
                } else {
                    log.info("Could not allocate other clones, doing only my part of the job.");
                }
            }

            // Run the method and retrieve the result
            Object result;
            Long execDuration;
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
                    log.info("clientDOis classloader: " + clientDOis.getClassLoader());

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

            if (withMultipleClones) {
                // Wait for all the clones to finish execution before returning the result
                waitForThreadsToBeReady();
                log.debug("All servers finished execution, send result back.");

                // Kill the threads.
                sendCommandToAllThreads(-1);

                synchronized (responsesFromServers) {
                    Array.set(responsesFromServers, 0, result); // put the result of the main clone as the
                    // first element of the array
                }

                // Call the reduce function implemented by the developer to combine the partial results.
                try {
                    // Array of the returned type
                    Class<?> arrayReturnType =
                            Array.newInstance(returnType, numberOfCloneHelpers + 1).getClass();
                    Method runMethodReduce =
                            objClass.getDeclaredMethod(methodName + "Reduce", arrayReturnType);
                    runMethodReduce.setAccessible(true);
                    log.info("Reducing the results using the method: " + runMethodReduce.getName());

                    Object reducedResult =
                            runMethodReduce.invoke(objToExecute, new Object[]{responsesFromServers});
                    result = reducedResult;

                    log.info("The reduced result: " + reducedResult);

                } catch (Exception e) {
                    log.error("Impossible to reduce the result");
                    e.printStackTrace();
                }
            }

            // If this clientIs the main VM send back also the object to execute,
            // otherwise the helper VMs don't need to send it back.
            if (cloneHelperId == 0) {
                return new ResultContainer(objToExecute, result, getObjectDuration, execDuration);
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


    private void waitForThreadsToBeReady() {
        // Wait for the threads to be ready
        synchronized (nrClonesReady) {
            while (nrClonesReady.get() < numberOfCloneHelpers) {
                try {
                    nrClonesReady.wait();
                } catch (InterruptedException e) {
                    log.info("Thread wait() was interrupted, putting back to sleep...");
                }
            }

            nrClonesReady.set(0);
        }
    }

    private void sendCommandToAllThreads(int command) {
        synchronized (pausedHelper) {
            for (int i = 1; i < numberOfCloneHelpers + 1; i++) {
                pausedHelper[i] = false;
            }
            requestFromMainServer = command;
            pausedHelper.notifyAll();
        }
    }

    /**
     * @return true if the jar file already exists in the AS.
     */
    private boolean appExists() {
        synchronized (syncAppExistsObject) {
            log.info("Checking if the jar file '" + jarFilePath + "' with size " + jarLength + " exists");
            File jarFile = new File(jarFilePath);

            if (jarFile.exists() && jarFile.length() == jarLength) {
                apkMapSemaphore.put(jarName, new CountDownLatch(0));
                return true;
            }

            if (apkMap.get(jarName) == null || apkMap.get(jarName) != jarLength) {
                apkMap.put(jarName, (int) jarLength);
                apkMapSemaphore.put(jarName, new CountDownLatch(1));
                return false;
            }

            return true;
        }
//        return jarFile.exists() && jarFile.length() == jarLength;
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
        if (!appFolder.mkdirs()) {
            log.warn("Could not create the app folder: " + appFolder.getAbsolutePath());
        }

        File jarFile = new File(appFolder, jarName + ".jar");
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(jarFile);
            byte[] buffer = new byte[4096];
            int totalRead = 0;
            long remaining = jarLength;
            int read;
            while ((read = clientIs.read(buffer, 0, (int) Math.min(buffer.length, remaining))) > 0) {
                fos.write(buffer, 0, read);
                totalRead += read;
                remaining -= read;
            }

            log.info("Succesfully read the " + totalRead + " bytes of the jar file, extracting now.");
            extractJarFile(appFolder, jarFile);

            // The client clientIs waiting for an ACK that the jar file was correctly received and extracted.
            clientOs.write(1);
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
                    if (!f.mkdirs()) {
                        log.warn("Could not create the folder: " + f.getAbsolutePath());
                    }
                } else {
                    if (!f.getParentFile().exists()) {
                        if (!f.getParentFile().mkdirs()) {
                            log.warn("Could not create the file: " + f.getAbsolutePath());
                        }
                    }
                    InputStream is = jar.getInputStream(jarEntry);
                    FileOutputStream fos2 = new FileOutputStream(f);
                    byte[] b = new byte[4096];
                    while (is.available() > 0) {
                        int read = is.read(b);
                        fos2.write(b, 0, read);
                        // fos2.write(clientIs.read());
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
        ClassLoader dOisLoader = clientDOis.getClassLoader();

        // ClassLoader[] loaders = new ClassLoader[] {appLoader, currentLoader, objLoader};
        ClassLoader[] loaders = new ClassLoader[]{currentLoader};
        listLoadedNativeLibs(loaders);

        loaders = new ClassLoader[]{appLoader};
        listLoadedNativeLibs(loaders);

        loaders = new ClassLoader[]{objLoader};
        listLoadedNativeLibs(loaders);

        loaders = new ClassLoader[]{dOisLoader};
        listLoadedNativeLibs(loaders);
    }

    private void listLoadedNativeLibs(ClassLoader[] classLoaders) {
        log.info("Libraries loaded by ClassLoader: " + classLoaders[0]);
        final String[] libraries = ClassScope.getLoadedLibraries(classLoaders);
        for (String library : libraries) {
            System.out.println(library);
        }
    }


    /**
     * Connect to the DS and ask for more VMs.<br>
     * The DS will reply with the IP address of the VMs<br>
     * <p>
     * Launch the threads to connect to the other VMs.<br>
     */
    private boolean connectToServerHelpers() {

        Socket socket = null;
        OutputStream os;
        InputStream is;
        ObjectOutputStream oos = null;
        ObjectInputStream ois = null;

        try {

            log.debug("Trying to connect to the DS - " + config.getDsIp() + ":" + config.getDsPort());

            // Connect to the directory service
            socket = new Socket(config.getDsIp(), config.getDsPort());
            os = socket.getOutputStream();
            is = socket.getInputStream();

            oos = new ObjectOutputStream(os);
            ois = new ObjectInputStream(is);

            log.debug("Connection established with the DS - " + config.getDsIp() + ":"
                    + config.getDsPort());

            // Ask for helper VMs
            os.write(RapidMessages.PARALLEL_REQ);
            oos.writeLong(AccelerationServer.vmId);
            oos.writeInt(numberOfCloneHelpers);
            oos.flush();

            ArrayList<Clone> cloneHelpers = (ArrayList<Clone>) ois.readObject();
            if (cloneHelpers.size() != numberOfCloneHelpers) {
                log.info("The DS could not start the needed clones, actually started: "
                        + cloneHelpers.size());
                return false;
            }

            // Assign the IDs to the new clone helpers
            log.info("The helper clones:");
            int cloneHelperId = 1;
            for (Clone c : cloneHelpers) {

                log.info(c.toString());

                // Start the thread that should connect to the clone helper
                (new VMHelperThread(config, cloneHelperId++, c)).start();
            }

            return true;

        } catch (Exception e) {
            log.error("Exception connecting to the manager: " + e);
        } catch (Error e) {
            log.error("Error connecting to the manager: " + e);
        } finally {
            RapidUtils.closeQuietly(ois);
            RapidUtils.closeQuietly(oos);
            RapidUtils.closeQuietly(socket);
        }

        return false;
    }


    /**
     * The thread taking care of communication with the VM helpers
     */
    private class VMHelperThread extends Thread {

        private String TAG = "ServerHelper-";
        private Configuration config;
        private Clone clone;
        private Socket mSocket;
        private OutputStream mOutStream;
        private InputStream mInStream;
        private ObjectOutputStream mObjOutStream;
        private DynamicObjectInputStream mObjInStream;

        // This id clientIs assigned to the clone helper by the main clone.
        // It clientIs needed for splitting the input when parallelizing a certain method (see for example
        // virusScanning).
        // To not be confused with the id that the AS has read from the config file.
        private int cloneHelperId;

        VMHelperThread(Configuration config, int cloneHelperId, Clone clone) {
            this.config = config;
            this.clone = clone;
            this.cloneHelperId = cloneHelperId;
            TAG = TAG + this.cloneHelperId;
        }

        @Override
        public void run() {

            try {

                // Try to connect to the VM helper.
                // If it clientIs not possible to connect stop running.
                if (!establishConnection()) {
                    // Try to close created sockets
                    closeConnection();
                    return;
                }

                // Send the cloneId to this clone.
                mOutStream.write(RapidMessages.CLONE_ID_SEND);
                mOutStream.write(cloneHelperId);

                while (true) {

                    synchronized (nrClonesReady) {
                        log.debug("Server Helpers started so far: " + nrClonesReady.addAndGet(1));
                        if (nrClonesReady.get() >= AppHandler.numberOfCloneHelpers)
                            nrClonesReady.notifyAll();
                    }

                    // wait() until the main server wakes up the thread then do something depending on the
                    // request
                    synchronized (pausedHelper) {
                        while (pausedHelper[cloneHelperId]) {
                            try {
                                pausedHelper.wait();
                            } catch (InterruptedException e) {
                                log.info("Thread wait() was interrupted, putting back to sleep");
                            }
                        }

                        pausedHelper[cloneHelperId] = true;
                    }

                    log.debug("Sending command: " + requestFromMainServer);

                    switch (requestFromMainServer) {

                        case RapidMessages.PING:
                            pingOtherServer();
                            break;

                        case RapidMessages.AC_REGISTER_AS:
                            mOutStream.write(RapidMessages.AC_REGISTER_AS);
                            mObjOutStream.writeObject(jarName);
                            mObjOutStream.writeLong(jarLength);
                            mObjOutStream.flush();

                            int response = mInStream.read();

                            if (response == RapidMessages.AS_APP_REQ_AC) {
                                // Send the APK file if needed
                                log.debug("Sending apk to the clone " + clone.getIp());

                                File apkFile = new File(jarFilePath);
                                FileInputStream fin = new FileInputStream(apkFile);
                                BufferedInputStream bis = new BufferedInputStream(fin);
                                int BUFFER_SIZE = 8192;
                                byte[] tempArray = new byte[BUFFER_SIZE];
                                int read;
                                int totalRead = 0;
                                log.debug("Sending apk");
                                while ((read = bis.read(tempArray, 0, tempArray.length)) > -1) {
                                    totalRead += read;
                                    mObjOutStream.write(tempArray, 0, read);
                                    log.debug("Sent " + totalRead + " of " + apkFile.length() + " bytes");
                                }
                                mObjOutStream.flush();
                                bis.close();
                            } else if (response == RapidMessages.AS_APP_PRESENT_AC) {
                                log.debug("Application already registered on clone " + clone.getIp());
                            }
                            break;

                        case RapidMessages.AC_OFFLOAD_REQ_AS:
                            log.debug("Asking VM " + clone.getIp() + " to parallelize the execution");

                            mOutStream.write(RapidMessages.AC_OFFLOAD_REQ_AS);

                            // Send the number of VMs needed.
                            // Since this clientIs a helper VM, only one clone should be requested.
                            mObjOutStream.writeInt(1);
                            mObjOutStream.writeObject(objToExecute);
                            mObjOutStream.writeObject(methodName);
                            mObjOutStream.writeObject(pTypes);
                            mObjOutStream.writeObject(pValues);
                            mObjOutStream.flush();

                            // This clientIs the response from the clone helper, which clientIs a partial result of the method
                            // execution. This partial result clientIs stored in an array, and will be later composed
                            // with the other partial results of the other clones to obtain the total desired
                            // result to be sent back to the phone.
                            Object cloneResult = mObjInStream.readObject();

                            ResultContainer container = (ResultContainer) cloneResult;

                            log.debug("Received response from clone ip: " + clone.getIp() + " port: "
                                    + clone.getPort());
                            log.debug("Writing in responsesFromServer in position: " + cloneHelperId);
                            synchronized (responsesFromServers) {
                                Array.set(responsesFromServers, cloneHelperId, container.functionResult);
                            }
                            break;

                        case -1:
                            closeConnection();
                            return;
                    }
                }
            } catch (IOException e) {
                log.error("IOException: " + e);
            } catch (ClassNotFoundException e) {
                log.error("ClassNotFoundException: " + e);
            } finally {
                closeConnection();
            }
        }

        private boolean establishConnection() {
            try {

                log.debug("Trying to connect to clone " + clone.getIp() + ":" + clone.getPort());

                mSocket = new Socket();
                mSocket.connect(new InetSocketAddress(clone.getIp(), clone.getPort()), 10 * 1000);

                mOutStream = mSocket.getOutputStream();
                mInStream = mSocket.getInputStream();
                mObjOutStream = new ObjectOutputStream(mOutStream);
                mObjInStream = new DynamicObjectInputStream(mInStream);

                log.debug("Connection established whith clone " + clone.getIp());

                return true;
            } catch (Exception e) {
                log.error("Exception not caught properly - " + e);
                return false;
            } catch (Error e) {
                log.error("Error not caught properly - " + e);
                return false;
            }
        }

        private void pingOtherServer() {
            try {
                // Send a message to the Server Helper (other server)
                log.debug("PING other server");
                mOutStream.write(eu.project.rapid.common.RapidMessages.PING);

                // Read and display the response message sent by server helper
                int response = mInStream.read();

                if (response == RapidMessages.PONG)
                    log.debug("PONG from other server: " + clone.getIp() + ":" + clone.getPort());
                else {
                    log.debug("Bad Response to Ping - " + response);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private void closeConnection() {
            RapidUtils.closeQuietly(mObjOutStream);
            RapidUtils.closeQuietly(mObjInStream);
            RapidUtils.closeQuietly(mSocket);
        }
    }
}
