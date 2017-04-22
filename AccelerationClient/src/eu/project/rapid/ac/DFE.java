package eu.project.rapid.ac;

import eu.project.rapid.ac.profilers.NetworkProfiler;
import eu.project.rapid.ac.profilers.Profiler;
import eu.project.rapid.ac.rm.AC_RM;
import eu.project.rapid.common.Clone;
import eu.project.rapid.common.RapidConstants.ExecLocation;
import eu.project.rapid.common.RapidConstants.REGIME;
import eu.project.rapid.common.RapidMessages;
import eu.project.rapid.common.RapidUtils;
import eu.project.rapid.gvirtusfe.Frontend;
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
import java.security.CodeSource;

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

    // GVirtuS frontend is responsible for running the CUDA code.
    private Frontend gVirtusFrontend;

    // Variables related to the app using the DFE
    private String jarFilePath;
    private String jarName; // The jar name without ".jar" extension
    private long jarSize;
    private File jarFile;
    private boolean onLine;

    // Socket and streams with the VM
    private Clone vm;
    private Socket vmSocket;
    private InputStream vmIs;
    private OutputStream vmOs;
    private ObjectOutputStream vmOos;
    private ObjectInputStream vmOis;

    private long prepareDataDuration = -1;

    // Constructor to be used by the AS
    private DFE(boolean serverSide) {
    }

    private DFE() {
        this(null);
    }

    // Constructor to be used by the application.
    private DFE(Clone vm) {
        // To prevent instantiating the DFE calling this constructor by Reflection call
        if (instance != null) {
            throw new IllegalStateException("Already initialized.");
        }

        config = new Configuration(DFE.class.getSimpleName(), REGIME.AC);
        dse = new DSE(config);

        // Create the folder where the client apps will keep their data.
        try {
            Utils.createDirIfNotExist(config.getRapidFolder());
        } catch (FileNotFoundException e) {
            log.error("Could not create folder " + config.getRapidFolder() + ": " + e);
        }

        // Get the jar file to send to the AS.
        createJarFile();

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
        // local variable increases performance by 25 percent according to
        // Joshua Bloch "Effective Java, Second Edition", p. 283-284
        DFE result = instance;

        if (result == null) {
            synchronized (DFE.class) {
                result = instance;
                if (result == null) {
                    instance = result = new DFE();
                }
            }
        }

        return result;
    }

    public static DFE getInstance(String vmIp) {
        DFE result = instance;

        if (result == null) {
            synchronized (DFE.class) {
                result = instance;
                if (result == null) {
                    Clone vm = new Clone("", vmIp);
                    vm.setCryptoPossible(true);
                    // vm.set
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

    private void registerWithAs() {

        try {
            vmOs.write(RapidMessages.AC_REGISTER_AS);
            vmOos.writeUTF(jarName);
            vmOos.writeLong(jarSize);
            vmOos.flush();
            int response = vmIs.read();
            if (response == RapidMessages.AS_APP_PRESENT_AC) {
                log.info("The app is already present on the AS, do nothing.");
            } else if (response == RapidMessages.AS_APP_REQ_AC) {
                log.info("Should send the app to the AS");
                sendApp(jarFile);
            }
        } catch (IOException e) {
            log.error("Could not send message to VM: " + e);
        }
    }

    /**
     * Send the jar file to the AS
     */
    private void sendApp(File jarFile) {

        log.info("Sending jar file " + jarFilePath + " to the AS");
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(jarFile);
            byte[] buffer = new byte[4096];
            int totalRead = 0;
            int read;
            while ((read = fis.read(buffer)) > -1) {
                vmOs.write(buffer, 0, read);
                totalRead += read;
            }

            vmIs.read();
            log.info("----- Successfully sent the " + totalRead + " bytes of the jar file.");
        } catch (FileNotFoundException e) {
            log.error("Could not read the jar file: " + e);
        } catch (IOException e) {
            log.error("Error while reading the jar file: " + e);
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                    log.error("Error while closing the fis of jar file: " + e);
                }
            }
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

                // // FIXME: Remove the following two lines and use the VM received from the AC_RM.
                // // Figure out why AC_RM returns VM with IP 127.0.0.1
                // vm = new Clone("FIXME", "10.0.0.3");
                // log.info("------ FIXME: temporarely using this VM while trying to solve the AC_RM
                // problem: "
                // + vm);

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

                if (config.getGvirtusIp() == null) {
                    // If gvirtusIp is null, then gvirtus backend is running on the physical machine where
                    // the VM is running.
                    // Try to find a way here to get the ip address of the physical machine.
                    // config.setGvirtusIp(TODO: ip address of the physical machine where the VM is running);
                }

                // FIXME uncomment this, commented just to perform quick tests.
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
                log.debug("Network profiling finished.");
                registerWithAs();
            }

            // if (config.getGvirtusIp() != null) {
            // // Create a gvirtus frontend object that is responsible for executing the CUDA code.
            // gVirtusFrontend = new Frontend(config.getGvirtusIp(), config.getGvirtusPort());
            // }
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
            // Disable this on release version.
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
                 BufferedReader br = new BufferedReader(isr);) {

                String line = null;
                while ((line = br.readLine()) != null)
                    System.out.println(line);
            } catch (IOException e) {
                log.error("Error while reading stream: " + e);
            }

        }
    }

    public Object execute(Method m, Object o) {
        return execute(m, (Object[]) null, o);
    }

    /**
     *
     */
    public Object execute(Method m, Object[] values, Object o) {
        Object result = null;

        // First find where to execute the method.
        ExecLocation execLocation = dse.findExecLocationDbCache(jarName, m.getName());

        if (execLocation == ExecLocation.LOCAL) {
            try {
                result = executeLocally(m, values, o);
            } catch (IllegalArgumentException | IllegalAccessException | InvocationTargetException e) {
                log.error("Error while calling executeLocally: " + e);
                e.printStackTrace();
            }
        } else if (execLocation == ExecLocation.REMOTE) {
            try {
                result = executeRemotely(m, values, o);
                if (result instanceof InvocationTargetException) {
                    // The remote execution throwed an exception, try to run the method locally.
                    log.warn("The returned result was InvocationTargetException. Running the method locally");
                    result = executeLocally(m, values, o);
                }
            } catch (IllegalArgumentException | SecurityException | IllegalAccessException
                    | InvocationTargetException | ClassNotFoundException | NoSuchMethodException
                    | IOException e) {
                log.error("Error while calling executeRemotely: " + e);
                e.printStackTrace();

                log.warn("Running the method locally");
                try {
                    result = executeLocally(m, values, o);
                } catch (IllegalArgumentException | IllegalAccessException | InvocationTargetException e1) {
                    log.warn("The local execution failed: " + e1);
                    e1.printStackTrace();
                }
            }
        }

        return result;

    }

    /**
     * Execute the method locally
     *
     * @param m
     * @param pValues
     * @param o
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalAccessException
     * @throws InvocationTargetException
     */
    private Object executeLocally(Method m, Object[] pValues, Object o)
            throws IllegalArgumentException, IllegalAccessException, InvocationTargetException {

        // Start tracking execution statistics for the method
        Profiler profiler = new Profiler(jarName, m.getName(), ExecLocation.LOCAL, config);
        profiler.start();

        // Make sure that the method is accessible
        Object result = null;
        long startTime = System.nanoTime();
        m.setAccessible(true);
        result = m.invoke(o, pValues); // Access it
        long pureLocalDuration = System.nanoTime() - startTime;
        log.info("LOCAL " + m.getName() + ": Actual Invocation duration - "
                + pureLocalDuration / Constants.MEGA + "ms");

        // Collect execution statistics
        profiler.stop(pureLocalDuration);

        return result;
    }

    /**
     * Execute method remotely
     *
     * @param m
     * @param pValues
     * @param o
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalAccessException
     * @throws InvocationTargetException
     * @throws NoSuchMethodException
     * @throws ClassNotFoundException
     * @throws SecurityException
     * @throws IOException
     */
    private Object executeRemotely(Method m, Object[] pValues, Object o)
            throws IllegalArgumentException, IllegalAccessException, InvocationTargetException,
            SecurityException, ClassNotFoundException, NoSuchMethodException, IOException {
        Object result = null;

        try {
            // long s = System.nanoTime();
            Method prepareDataMethod = o.getClass().getDeclaredMethod("prepareDataOnClient");
            prepareDataMethod.setAccessible(true);
            // RapidUtils.sendAnimationMsg(config, RapidMessages.AC_PREPARE_DATA);
            long s = System.nanoTime();
            prepareDataMethod.invoke(o);
            prepareDataDuration = System.nanoTime() - s;

        } catch (NoSuchMethodException e) {
            log.warn("The method prepareDataOnClient() does not exist");
        }

        // Start tracking execution statistics for the method
        Profiler profiler = new Profiler(jarName, m.getName(), ExecLocation.REMOTE, config);
        profiler.start();

        // try {
        long startTime = System.nanoTime();
        vmOs.write(RapidMessages.AC_OFFLOAD_REQ_AS);
        ResultContainer resultContainer = sendAndExecute(m, pValues, o);
        result = resultContainer.functionResult;

        long remoteDuration = System.nanoTime() - startTime;
        log.info("REMOTE " + m.getName() + ": Actual Send-Receive duration - "
                + remoteDuration / Constants.MEGA + "ms");
        // Collect execution statistics
        profiler.stop(prepareDataDuration, resultContainer.pureExecutionDuration);
        // } catch (Exception e) {
        // // No such host exists, execute locally
        // log.error("REMOTE ERROR: " + m.getName() + ": " + e);
        // e.printStackTrace();
        // result = executeLocally(m, pValues, o);
        // }

        return result;
    }

    /**
     * Send the object, the method to be executed and parameter values to the remote server for
     * execution.
     *
     * @param m       method to be executed
     * @param pValues parameter values of the remoted method
     * @param o       the remoted object
     * @return result of the remoted method or an exception that occurs during execution
     * @throws IOException
     * @throws ClassNotFoundException
     * @throws NoSuchMethodException
     * @throws InvocationTargetException
     * @throws IllegalAccessException
     * @throws SecurityException
     * @throws IllegalArgumentException
     */
    private ResultContainer sendAndExecute(Method m, Object[] pValues, Object o)
            throws IOException, ClassNotFoundException, IllegalArgumentException, SecurityException,
            IllegalAccessException, InvocationTargetException, NoSuchMethodException {

        // Send the object itself
        sendObject(o, m, pValues);

        // Read the results from the server
        log.info("Read Result");

        long startSend = System.nanoTime();
        // long startRx = NetworkProfiler.getProcessRxBytes();
        Object response = vmOis.readObject();

        // Estimate the perceived bandwidth
        // NetworkProfiler.addNewDlRateEstimate(NetworkProfiler.getProcessRxBytes() - startRx,
        // System.nanoTime() - startSend);

        ResultContainer container = (ResultContainer) response;

        Class<?>[] pTypes = {Remoteable.class};
        try {
            // Use the copyState method that must be defined for all Remoteable
            // classes to copy the state of relevant fields to the local object
            o.getClass().getMethod("copyState", pTypes).invoke(o, container.objState);
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
     * @param o
     * @param m
     * @param pValues
     * @throws IOException
     */
    private void sendObject(Object o, Method m, Object[] pValues) throws IOException {
        vmOos.reset();
        log.info("Write Object and data");

        // Send the number of VMs needed to execute the method
        int nrVMs = 1;
        vmOos.writeInt(nrVMs);

        // Send object for execution
        vmOos.writeObject(o);

        // Send the method to be executed
        // log.info("Write Method - " + m.getName());
        vmOos.writeObject(m.getName());

        // log.info("Write method parameter types");
        vmOos.writeObject(m.getParameterTypes());

        // log.info("Write method parameter values");
        vmOos.writeObject(pValues);
        vmOos.flush();

        // totalTxBytesObject = NetworkProfiler.getProcessTxBytes() - startTx;
    }

    public void destroy() {
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
     * @return the gvirtusFrontend
     */
    public Frontend getGvirtusFrontend() {
        return gVirtusFrontend;
    }

    /**
     * @param gvirtusFrontend the gvirtusFrontend to set
     */
    public void setGvirtusFrontend(Frontend gvirtusFrontend) {
        this.gVirtusFrontend = gvirtusFrontend;
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

    public void setUserChoice(String userChoice) {
        switch (userChoice) {
            case "LOCAL":
                this.setUserChoice(ExecLocation.LOCAL);
                break;

            case "REMOTE":
                this.setUserChoice(ExecLocation.REMOTE);
                break;

            case "DYNAMIC":
                this.setUserChoice(ExecLocation.DYNAMIC);
                break;

            default:
                log.error("Wrong user choice: " + userChoice + ". Defaulting to " + ExecLocation.DYNAMIC);
                this.setUserChoice(ExecLocation.DYNAMIC);
                break;
        }
    }



    public String getRapidFolder() {
        return config.getRapidFolder();
    }
}
