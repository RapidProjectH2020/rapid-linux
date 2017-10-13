package eu.project.rapid.ac;

import eu.project.rapid.ac.profilers.NetworkProfiler;
import eu.project.rapid.ac.profilers.Profiler;
import eu.project.rapid.ac.rm.AC_RM;
import eu.project.rapid.common.Clone;
import eu.project.rapid.common.RapidConstants.ExecLocation;
import eu.project.rapid.common.RapidConstants.REGIME;
import eu.project.rapid.common.RapidMessages;
import eu.project.rapid.common.RapidUtils;
import eu.project.rapid.utils.Configuration;
import eu.project.rapid.utils.Constants;
import eu.project.rapid.utils.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.CodeSource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The class that handles the task execution using Java reflection.<br>
 * Implemented as a singleton.
 *
 * @author sokol
 */
public class DFE {

    // The only instance of the DFE
    private static volatile DFE instance = null;

    private final static Logger log = LogManager.getLogger(DFE.class.getSimpleName());

    private Configuration config;

    // Design and Space Explorer is responsible for deciding where to execute the method.
    private DSE dse;
    private ExecLocation userChoice = ExecLocation.DYNAMIC;

    // Variables related to the app using the DFE
    private String jarFilePath;
    private String jarName; // The jar name without ".jar" extension
    private long jarSize;
    private File jarFile;
    static boolean onLine;

    // Socket and streams with the VM
    private Clone vm;
    private Socket vmSocket;
    private InputStream vmIs;
    private OutputStream vmOs;
    private ObjectOutputStream vmOos;
    private ObjectInputStream vmOis;

    private long prepareDataDuration = -1;

    private static boolean isDFEActive = false;
    private static final int nrTaskRunners = 3;
    private static ExecutorService threadPool;
    private static BlockingDeque<Task> tasks = new LinkedBlockingDeque<>();
    private static AtomicInteger taskId = new AtomicInteger();
    private static Map<Integer, BlockingDeque<Object>> tasksResultsMap = new HashMap<>();

    // Constructor to be used by the AS
    private DFE(boolean serverSide) {
    }

    // Constructor to be used by the application.
    private DFE(Clone vm) {
        // To prevent instantiating the DFE calling this constructor by Reflection call
        if (instance != null) {
            throw new IllegalStateException("Already initialized.");
        }

        config = new Configuration(DFE.class.getSimpleName(), REGIME.AC);
        threadPool = Executors.newFixedThreadPool(nrTaskRunners);

        // Create the folder where the client apps will keep their data.
        try {
            Utils.createDirIfNotExist(config.getRapidFolder());
        } catch (FileNotFoundException e) {
            log.error("Could not create folder " + config.getRapidFolder() + ": " + e);
        }

        // Get the jar file to send to the AS.
        createJarFile();
        dse = DSE.getInstance(jarName);

        // If vm != null it means that we are performing some tests with a predefined VM.
        if (vm == null) {
            // Starts the AC_RM component, which is unique for each device and handles the registration
            // mechanism with the DS.
            startAcRm();

            // Talk with the AC_RM to get the info about the VM to connect to.
            this.vm = getVmFromAC_RM();
        } else {
            this.vm = vm;
        }

        // Trigger the registration process with the VM.
        establishConnection();
    }

    private void createJarFile() {
        // Get the name of the jar file of this app. It will be needed so that the jar can be sent
        // to the AS for offload executions.
        CodeSource codeSource = DFE.class.getProtectionDomain().getCodeSource();
        if (codeSource != null) {
            jarFilePath = codeSource.getLocation().getFile();// getPath();
            jarFile = new File(jarFilePath);
            jarSize = jarFile.length();
            log.info("jarFilePath: " + jarFilePath + ", jarSize: " + jarSize + " bytes");
            jarName = jarFilePath.substring(jarFilePath.lastIndexOf(File.separator) + 1,
                    jarFilePath.lastIndexOf("."));
        } else {
            log.warn("Could not get the CodeSource object needed to get the executable of the app");
        }
    }

    /**
     * Double check locking singleton implementation.
     *
     * @return The only instance of the DFE
     */
    public static DFE getInstance() {
        return getInstance(null);
    }

    public static DFE getInstance(String vmIp) {
        // local variable increases performance by 25 percent according to
        // Joshua Bloch "Effective Java, Second Edition", p. 283-284
        DFE result = instance;

        if (result == null) {
            synchronized (DFE.class) {
                result = instance;
                if (result == null) {
                    Clone vm = null;
                    if (vmIp != null) {
                        vm = new Clone("", vmIp);
                        vm.setCryptoPossible(true);
                    }
                    instance = result = new DFE(vm);
                }
            }
        }

        return result;
    }

    private boolean connectWitAs() {
        try {
            log.info("Connecting with VM in clear...");
            vmSocket = new Socket(vm.getIp(), vm.getPort());
            vmOs = vmSocket.getOutputStream();
            vmIs = vmSocket.getInputStream();
            vmOos = new ObjectOutputStream(vmOs);
            vmOis = new ObjectInputStream(vmIs);

            return true;

        } catch (IOException e) {
            log.error("Could not connect to VM: " + e);
            return false;
        }
    }

    private boolean connectWitAsSsl() {
        log.info("Connecting with VM using SSL...");

        try {
            // Creating Client Sockets
            vmSocket = config.getSslFactory().createSocket(vm.getIp(), vm.getSslPort());

            // Initializing the streams for Communication with the Server
            vmOs = vmSocket.getOutputStream();
            vmIs = vmSocket.getInputStream();
            vmOos = new ObjectOutputStream(vmOs);
            vmOis = new ObjectInputStream(vmIs);

            return true;

        } catch (Exception e) {
            System.out.println("Error while connecting with SSL with the VM: " + e);
            e.printStackTrace();
            return false;
        }
    }

    private void registerWithAs(InputStream is, OutputStream os, ObjectOutputStream oos) {

        try {
            os.write(RapidMessages.AC_REGISTER_AS);
            oos.writeUTF(jarName);
            oos.writeLong(jarSize);
            oos.flush();
            int response = is.read();
            if (response == RapidMessages.AS_APP_PRESENT_AC) {
                log.info("The app is already present on the AS, do nothing.");
            } else if (response == RapidMessages.AS_APP_REQ_AC) {
                log.info("Should send the app to the AS");
                sendApp(is, os);
            }
        } catch (IOException e) {
            log.error("Could not send message to VM: " + e);
        }
    }

    /**
     * Send the jar file to the AS
     */
    private void sendApp(InputStream is, OutputStream os) {

        log.info("Sending jar file " + jarFilePath + " to the AS");
        try (FileInputStream fis = new FileInputStream(jarFile)) {
            byte[] buffer = new byte[4096];
            int totalRead = 0;
            int read;
            while ((read = fis.read(buffer)) > -1) {
                os.write(buffer, 0, read);
                totalRead += read;
            }
            is.read();
            log.info("----- Successfully sent the " + totalRead + " bytes of the jar file.");
        } catch (FileNotFoundException e) {
            log.error("Could not read the jar file: " + e);
        } catch (IOException e) {
            log.error("Error while reading the jar file: " + e);
        }
    }

    /**
     * Close the connection with the VM.
     */
    private void closeConnection() {
        RapidUtils.closeQuietly(vmOis);
        RapidUtils.closeQuietly(vmOos);
        RapidUtils.closeQuietly(vmSocket);
    }

    /**
     * Initialize the variables that were stored by previous executions, e.g. the userID, the VM to
     * connect to, etc. Usually these variables will be provided by the AC_RM.
     */
    private Clone getVmFromAC_RM() {
        // Connect to the AC_RM, which is a server component running on this machine, and ask for the
        // parameters.

        Clone vm = null;
        boolean connectionAcRmSuccess = false;
        while (!connectionAcRmSuccess) {
            try (Socket socket = new Socket(InetAddress.getLocalHost(), config.getAcRmPort());
                 OutputStream os = socket.getOutputStream();
                 InputStream is = socket.getInputStream();
                 ObjectOutputStream oos = new ObjectOutputStream(os);
                 ObjectInputStream ois = new ObjectInputStream(is)) {

                // Ask the AC_RM for the VM to connect to.
                os.write(RapidMessages.AC_HELLO_AC_RM);
                long userID = ois.readLong();
                try {
                    vm = (Clone) ois.readObject();
                } catch (ClassNotFoundException e) {
                    log.error("Could not properly receive the VM info from the AC_RM: " + e);
                }
                log.info("------ Finished talking to AC_RM, received userID=" + userID + " and VM=" + vm);

                 // FIXME: Remove the following two lines and use the VM received from the AC_RM.
                 // Figure out why AC_RM returns VM with IP 127.0.0.1
//                 vm = new Clone("FIXME", "127.0.0.1");
//                 log.info("------ FIXME: temporarily using this VM while trying to solve the AC_RM problem: " + vm);

                connectionAcRmSuccess = true;
            } catch (IOException e) {
                log.warn("AC_RM is not started yet, retrying after 2 seconds");
                try {
                    Thread.sleep(2 * 1000);
                } catch (InterruptedException e1) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        return vm;
    }

    private void establishConnection() {
        if (vm == null) {
            log.warn("It was not possible to get a VM, only local execution will be possible.");
        } else {
            config.setVm(vm);
            log.info("Received VM " + vm + " from AC_RM, connecting now...");

            if (config.isConnectSsl() && vm.isCryptoPossible()) {
                onLine = connectWitAsSsl();
            }

            if (!onLine) {
                log.error("It was not possible to connect with the VM using SSL, connecting in clear...");
                onLine = connectWitAs();
            }

            if (onLine) {
                log.info("Connected to VM");

                // FIXME uncomment this (if it's commented). Commented just to perform quick tests.
                NetworkProfiler.startNetworkMonitoring(config);

                // Start waiting for the network profiling to be finished.
                // Wait maximum for 10 seconds and then give up, since something could have gone wrong.
                long startWaiting = System.currentTimeMillis();
                while (NetworkProfiler.rtt == NetworkProfiler.rttInfinite
                        || NetworkProfiler.lastUlRate == -1 || NetworkProfiler.lastDlRate == -1) {

                    if ((System.currentTimeMillis() - startWaiting) > 10 * 1000) {
                        log.warn("Too much time for the network profiling to finish, postponing for later.");
                        break;
                    }

                    try {
                        Thread.sleep(1000);
                        log.debug("Waiting for network profiling to finish...");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                log.debug("Network profiling finished: rtt=" +
                        NetworkProfiler.rtt + ", ulRate=" + NetworkProfiler.lastUlRate +
                        ", dlRate=" + NetworkProfiler.lastDlRate);
                registerWithAs(vmIs, vmOs, vmOos);

                // Start 10 TaskRunner threads that will handle the task dispatching process.
                for (int i = 0; i < nrTaskRunners; i++) {
                    threadPool.submit(new TaskRunner(i));
                }
            }
        }
    }

    /**
     * Start the common RM component of all application clients.
     */
    private void startAcRm() {
        log.info("User dir: " + System.getProperty("user.dir"));
        log.info("Classpath: " + System.getProperty("java.class.path"));
        log.info("AC_RM class: " + AC_RM.class.getName());

        // java.class.path returns the classpath
        ProcessBuilder acRm = new ProcessBuilder("java", "-cp", System.getProperty("java.class.path"),
                AC_RM.class.getName());

        try {
            Process runAcRm = acRm.start();

            // Start threads to read the streams of the new process.
            // These threads will keep this app in the running state, even if the app has finished
            // processing.
            // FIXME: Disable this on release version.
            new StreamThread(runAcRm.getInputStream()).start();
            new StreamThread(runAcRm.getErrorStream()).start();

            log.info("AC_RM started");

        } catch (IOException e) {
            log.error("Could not start the AC_RM: " + e);
        }
    }

    private class StreamThread extends Thread {

        private InputStream is;

        StreamThread(InputStream stream) {
            this.is = stream;
        }

        @Override
        public void run() {

            try (InputStreamReader isr = new InputStreamReader(is);
                 BufferedReader br = new BufferedReader(isr)) {

                String line;
                while ((line = br.readLine()) != null)
                    System.out.println(line);
            } catch (IOException e) {
                log.error("Error while reading stream: " + e);
            }

        }
    }

    public Object execute(Method m, Object o) {
        return execute(m, null, o);
    }

    /**
     *
     */
    public Object execute(Method m, Object[] pValues, Object o) {

        Object result = null;
        try {
            int id = taskId.incrementAndGet();
            tasksResultsMap.put(id, new LinkedBlockingDeque<>());
            log.info("Adding task on the tasks blocking queue...");
            tasks.put(new Task(id, m, pValues, o));
            log.info("Task added");

            log.info("Waiting for the result of the task with id=" + id +
                    " to be inserted in the queue by the working thread...");
            result = tasksResultsMap.get(id).take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return result;
    }

    private class Task {
        Method m;
        Object[] pValues;
        Object o;
        int id;

        Task(int id, Method m, Object[] pValues, Object o) {
            this.id = id;
            this.m = m;
            this.pValues = pValues;
            this.o = o;
        }
    }

    private class TaskRunner implements Runnable {
        private final String TAG;

        TaskRunner(int id) {
            TAG = "DFE-TaskRunner-" + id + " ";
        }

        @Override
        public void run() {
            try (Socket s = new Socket(vm.getIp(), vm.getPort());
                 OutputStream os = s.getOutputStream();
                 InputStream is = s.getInputStream();
                 ObjectOutputStream oos = new ObjectOutputStream(os);
                 ObjectInputStream ois = new ObjectInputStream(is)) {

                // I know that the DFE main thread has already sent the JAR file.
                // However, I need to tell the listening threads which application is being connected,
                // so that they can load the classes and libraries.
                registerWithAs(is, os, oos);

                while (true) {
                    try {
                        log.info(TAG + "Waiting for task...");
                        Task task = tasks.take();

                        log.info(TAG + "Got a task, executing...");
                        Object result = runTask(task, os, ois, oos);
                        log.info(TAG + "Task with id=" + task.id +
                                " finished execution, putting result on the resultMap...");
                        tasksResultsMap.get(task.id).put(result != null ? result : new Object());
                        log.info(TAG + "Result inserted on the resultMap.");
                    } catch (InterruptedException e) {
                        if (!isDFEActive) {
                            break;
                        } else {
                            Thread.currentThread().interrupt();
                        }
                    }
                }

            } catch (UnknownHostException e) {
                log.error(TAG + "UnknownHostException while connecting: " + e);
            } catch (IOException e) {
                log.error(TAG + "IOException while connecting: " + e);
            }
        }

        private Object runTask(Task task, OutputStream os, ObjectInputStream ois, ObjectOutputStream oos) {
            Object result = null;

            ExecLocation execLocation = dse.findExecLocationDbCache(jarName, task.m.getName());
            if (execLocation.equals(ExecLocation.LOCAL)) {
                log.info(TAG + "Should run the method locally...");
                result = executeLocally(task);
            } else if (execLocation.equals(ExecLocation.REMOTE)) {
                try {
                    result = executeRemotely(task, os, ois, oos);
                    if (result instanceof InvocationTargetException) {
                        // The remote execution throwed an exception, try to run the method locally.
                        log.error(TAG + "The result was InvocationTargetException. Running the method locally...");
                        result = executeLocally(task);
                    }
                } catch (IllegalArgumentException | SecurityException e) {
                    log.error(TAG + "Error while trying to run the method remotely: " + e);
                }
            }
            return result;
        }

        /**
         * Execute the method locally
         *
         * @return
         * @throws IllegalArgumentException
         * @throws IllegalAccessException
         * @throws InvocationTargetException
         */
        private Object executeLocally(Task task) {
            // Start tracking execution statistics for the method
            Profiler profiler = new Profiler(jarName, task.m.getName(), ExecLocation.LOCAL, config);
            profiler.start();

            Object result = null; // Access it
            try {
                // Make sure that the method is accessible
                long startTime = System.nanoTime();
                task.m.setAccessible(true);
                result = task.m.invoke(task.o, task.pValues);
                long pureLocalDuration = System.nanoTime() - startTime;
                log.info("LOCAL " + task.m.getName() + ": Actual Invocation duration - " + pureLocalDuration / Constants.MEGA + "ms");

                // Collect execution statistics
                profiler.stopAndSave(pureLocalDuration);
            } catch (IllegalAccessException | InvocationTargetException | IllegalArgumentException e) {
                log.error("LOCAL execution failed: " + e);
                profiler.stopAndDiscard();
            }

            return result;
        }

        /**
         * Execute method remotely
         *
         * @return
         * @throws IllegalArgumentException
         * @throws IllegalAccessException
         * @throws InvocationTargetException
         * @throws NoSuchMethodException
         * @throws ClassNotFoundException
         * @throws SecurityException
         * @throws IOException
         */
        private Object executeRemotely(Task task, OutputStream os, ObjectInputStream ois, ObjectOutputStream oos) {

            try {
                Method prepareDataMethod = task.o.getClass().getDeclaredMethod("prepareDataOnClient");
                prepareDataMethod.setAccessible(true);
                // RapidUtils.sendAnimationMsg(config, RapidMessages.AC_PREPARE_DATA);
                long s = System.nanoTime();
                prepareDataMethod.invoke(task.o);
                prepareDataDuration = System.nanoTime() - s;

            } catch (NoSuchMethodException e) {
                log.warn("The method prepareDataOnClient() does not exist");
            } catch (IllegalAccessException | InvocationTargetException e) {
                log.warn("Exception calling the prepareDataOnClient() method: " + e);
            }

            // Start tracking execution statistics for the method
            Profiler profiler = new Profiler(jarName, task.m.getName(), ExecLocation.REMOTE, config);
            profiler.start();

            Object result;
            long startTime = System.nanoTime();
            try {
                oos.write(RapidMessages.AC_OFFLOAD_REQ_AS);
                ResultContainer resultContainer = sendAndExecute(task, ois, oos);
                result = resultContainer.functionResult;

                long remoteDuration = System.nanoTime() - startTime;
                log.info("REMOTE " + task.m.getName() + ": Actual Send-Receive duration - "
                        + remoteDuration / Constants.MEGA + "ms");
                // Collect execution statistics
                profiler.stopAndSave(prepareDataDuration, resultContainer.pureExecutionDuration);
            } catch (Exception e) {
                // No such host exists, execute locally
                log.error("REMOTE ERROR: " + task.m.getName() + ": " + e);
                e.printStackTrace();
                profiler.stopAndDiscard();
                result = executeLocally(task);
            }

            return result;
        }

        /**
         * Send the object, the method to be executed and parameter values to the remote server for
         * execution.
         *
         * @return result of the remoted method or an exception that occurs during execution
         * @throws IOException
         * @throws ClassNotFoundException
         * @throws NoSuchMethodException
         * @throws InvocationTargetException
         * @throws IllegalAccessException
         * @throws SecurityException
         * @throws IllegalArgumentException
         */
        private ResultContainer sendAndExecute(Task task,
                                               ObjectInputStream ois, ObjectOutputStream oos)
                throws IOException, ClassNotFoundException, IllegalArgumentException, SecurityException,
                IllegalAccessException, InvocationTargetException, NoSuchMethodException {

            // Send the object itself
            sendObject(task, oos);

            // Read the results from the server
            log.info("Read Result");

            long startSend = System.nanoTime();
            // long startRx = NetworkProfiler.getProcessRxBytes();
            Object response = ois.readObject();

            // Estimate the perceived bandwidth
            // NetworkProfiler.addNewDlRateEstimate(NetworkProfiler.getProcessRxBytes() - startRx,
            // System.nanoTime() - startSend);

            ResultContainer container = (ResultContainer) response;

            Class<?>[] pTypes = {Remoteable.class};
            try {
                // Use the copyState method that must be defined for all Remoteable
                // classes to copy the state of relevant fields to the local object
                task.o.getClass().getMethod("copyState", pTypes).invoke(task.o, container.objState);
            } catch (NullPointerException e) {
                // Do nothing - exception happened remotely and hence there is
                // no object state returned.
                // The exception will be returned in the function result anyway.
                log.warn("Exception received from remote server - " + container.functionResult);
            }

            return container;

            // result = container.functionResult;
            // long mPureRemoteDuration = container.pureExecutionDuration;
            //
            // // Estimate the perceived bandwidth
            // // NetworkProfiler.addNewUlRateEstimate(totalTxBytesObject, container.getObjectDuration);
            //
            // log.info("Finished remote execution");
            //
            // return result;
        }

        /**
         * Send the object (along with method and parameters) to the remote server for execution
         *
         * @throws IOException
         */
        private void sendObject(Task task, ObjectOutputStream oos) throws IOException {
            oos.reset();
            log.info("Write Object and data");

            // Send the number of VMs needed to execute the method
            int nrVMs = 1;
            oos.writeInt(nrVMs);

            // Send object for execution
            oos.writeObject(task.o);

            // Send the method to be executed
            // log.info("Write Method - " + m.getName());
            oos.writeObject(task.m.getName());

            // log.info("Write method parameter types");
            oos.writeObject(task.m.getParameterTypes());

            // log.info("Write method parameter values");
            oos.writeObject(task.pValues);
            oos.flush();

            // totalTxBytesObject = NetworkProfiler.getProcessTxBytes() - startTx;
        }
    }


    public void destroy() {
        isDFEActive = false;
        threadPool.shutdownNow();
        instance = null;
        closeConnection();
    }

    /**
     * @return the config
     */
    public Configuration getConfig() {
        return config;
    }

    /**
     * @param config the config to set
     */
    public void setConfig(Configuration config) {
        this.config = config;
    }

    /**
     * @return the userChoice
     */
    public ExecLocation getUserChoice() {
        return userChoice;
    }

    /**
     * @param userChoice the userChoice to set
     */
    public void setUserChoice(ExecLocation userChoice) {
        this.userChoice = userChoice;
        if (dse != null) {
            dse.setUserChoice(userChoice);
        }
    }

    /**
     * Choose if connection should be encrypted or not.
     * @param encrypted true if connection should be encrypted, false if connection should be cleartext.
     */
    public void setConnEncrypted(boolean encrypted) {
        config.setConnectSsl(encrypted);
    }

    public String getRapidFolder() {
        return config.getRapidFolder();
    }


    public ExecLocation getLastExecLocation(String methodName) {
        return dse.getLastExecLocation(methodName);
    }

    public long getLastExecDuration(String methodName) {
        return dse.getLastExecDuration(methodName);
    }
}
