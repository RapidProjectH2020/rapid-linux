package eu.project.rapid.ac.rm;

import eu.project.rapid.common.Clone;
import eu.project.rapid.common.RapidConstants;
import eu.project.rapid.common.RapidConstants.REGIME;
import eu.project.rapid.common.RapidMessages;
import eu.project.rapid.common.RapidUtils;
import eu.project.rapid.utils.Configuration;
import eu.project.rapid.utils.Constants;
import eu.project.rapid.utils.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class is the common component of the AC project. It takes care of registering with the DS.
 * Is started by the first application that runs on the machine. When this class is started the
 * first time it launches a server on the given port so the next application that tries to start the
 * AC_RM again will fail. The AC_RM will register the client with the DS according to user
 * preferences (build a GUI or through preference files). In particular, the AC_RM will choose to
 * connect to the previous VM or to ask a new VM.
 *
 * @author sokol
 */
public class AC_RM {

    private static final Logger log = LogManager.getLogger(AC_RM.class.getSimpleName());

    private static Configuration config;
    private static Properties sharedPrefs;
    private static Clone vm;
    // private String slamIp;
    private static long myId = -1;
    private final String prevVmFileName = "prevVm.ser"; // The file where the VM will be stored for
    // future use.
    private static String prevVmFilePath; // The full path of the file where the VM will
    // be stored for future use.
    private static boolean registerAsPrev;
    private static String jsonQosParams;

    private static ScheduledThreadPoolExecutor registrationScheduledPool =
            (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(1);
    // Every 2 minutes check if we have a VM. If not, try to re-register with the DS and SLAM.
    private static final int FREQUENCY_REGISTRATION = 2 * 60 * 1000;
    private static boolean registeringWithDs = false;
    private static boolean registeringWithSlam = false;

    private static ArrayList<String> vmmIPs;
    private static final int vmNrVCPUs = 1; // FIXME: number of CPUs on the VM
    private static final int vmMemSize = 512; // FIXME
    private static final int vmNrGpuCores = 1200; // FIXME

    public AC_RM() {
        log.info("Starting the AC_RM");
        // Read the configuration file to know the DS IP, the DS Port, and the port where the AC_RM
        // server should listen.
        config = new Configuration(AC_RM.class.getSimpleName(), REGIME.AC);
        registerAsPrev = config.isConnectToPrevVm();
        prevVmFilePath = config.getRapidFolder() + File.separator + prevVmFileName;
        sharedPrefs = new Properties();

        // Read previously saved information, like userID, etc.
        try {
            FileInputStream sharedPrefIs = new FileInputStream(new File(config.getSharedPrefsFile()));
            sharedPrefs.load(sharedPrefIs);
            myId =
                    Long.parseLong(sharedPrefs.getProperty(Constants.USER_ID_KEY, Constants.USER_ID_DEFAULT));
        } catch (IOException e) {
            log.error("Could not open shared prefs file: " + e);
            log.error("Will not be possible to ask for the prev vm, asking for a new one.");
            registerAsPrev = false;
        }

        if (registerAsPrev) {
            // Read the previously saved VM. If there is no previously saved VM, then the VM object
            // still remains null, so we'll ask for a new one when registering with the DS.
            readPrevVm();
        }

        // Read the qos parameters
        String qosParams = readQosParams().replace(" ", "").replace("\n", "");
        log.info("QoS params: " + qosParams);
        jsonQosParams = parseQosParams(qosParams);
        log.info("QoS params in JSON: " + jsonQosParams);
    }

    private void handleNewClient(Socket clientSocket) {
        new Thread(new ClientHandler(clientSocket)).start();
    }

    private synchronized static void registerWithDsAndSlam() {

        boolean registeredWithSlam;

        if (registerWithDs()) {
            // register with SLAM
            int vmmIndex = 0;
            if (vmmIPs != null) {
                do {
                    registeredWithSlam = registerWithSlam(vmmIPs.get(vmmIndex));
                    vmmIndex++;
                } while (!registeredWithSlam && vmmIndex < vmmIPs.size());
            }
        } else {
            log.error("Could not register with DS");
        }
    }

    /**
     * Register to the DS.<br>
     * <p>
     * If the VM is null then ask for a list of SLAMs that can provide a VM. Otherwise notify the DS
     * that we want to connect to the previous VM.
     */
    @SuppressWarnings("unchecked")
    private static boolean registerWithDs() {
        log.info("Registering with DS " + config.getDsIp() + ":" + config.getDsPort());
        int maxNrTimesToTry = 3;
        int nrTimesTried = 0;
        Socket dsSocket = null;
        boolean connectedWithDs = false;

        do {
            registeringWithDs = true;
            log.info("Registering with DS " + config.getDsIp() + ":" + config.getDsPort());
            try {
                dsSocket = new Socket();
                dsSocket.connect(new InetSocketAddress(config.getDsIp(), config.getDsPort()), 3000);
                log.info("Connected with DS");
                connectedWithDs = true;
            } catch (Exception e) {
                log.error("Could not connect with the DS: " + e);
                try {
                    Thread.sleep(10 * 1000);
                } catch (InterruptedException e1) {
                    Thread.currentThread().interrupt();
                }
            }
        } while (!connectedWithDs && ++nrTimesTried < maxNrTimesToTry);

        if (connectedWithDs) {
            try (ObjectOutputStream dsOut = new ObjectOutputStream(dsSocket.getOutputStream());
                 ObjectInputStream dsIn = new ObjectInputStream(dsSocket.getInputStream())) {

                if (registerAsPrev && myId != -1) { // Get a new VM
                    log.info("Registering as PREV with ID: " + myId + " with the DS...");
                    dsOut.writeByte(RapidMessages.AC_REGISTER_PREV_DS);
                    dsOut.writeLong(myId);
                } else { // Register and ask for the previous VM
                    log.info("Registering as NEW with ID:" + myId + " with the DS...");
                    dsOut.writeByte(RapidMessages.AC_REGISTER_NEW_DS);
                    log.info("Sending my ID: " + myId);
                    dsOut.writeLong(myId);

                    log.info("Sending VM details...");
                    // FIXME: should not use static values here.
                    dsOut.writeInt(vmNrVCPUs); // send vcpuNum as int
                    dsOut.writeInt(vmMemSize); // send memSize as int
                    dsOut.writeInt(vmNrGpuCores); // send gpuCores as int

                }

                dsOut.flush();

                // Receive message format: status (java byte), userId (java long), ipList (java object)
                byte status = dsIn.readByte();
                log.info("Return Status from DS: " + (status == RapidMessages.OK ? "OK" : "ERROR"));
                if (status == RapidMessages.OK) {
                    myId = dsIn.readLong();
                    log.info("New userId is: " + myId);

                    // Read the list of VMMs, which will be sorted based on free resources
                    vmmIPs = (ArrayList<String>) dsIn.readObject();

                    // Read the SLAM IP and port
                    String slamIp = dsIn.readUTF();
                    int slamPort = dsIn.readInt();
                    config.setSlamIp(slamIp);
                    config.setSlamPort(slamPort);
                    log.info("SLAM address is: " + slamIp + ":" + slamPort);

                    return true;
                }
            } catch (IOException e) {
                log.error("Could not connect with the DS: " + e);
            } catch (ClassNotFoundException e) {
                log.error("DS sent wrong object instead of array with VMM IPs as strings: " + e);
            }
        }

        registeringWithDs = false;
        return false;
    }


    /**
     * @param vmmIp The IP of the VMM to ask for a VM.
     */
    private static boolean registerWithSlam(String vmmIp) {

        int maxNrTimesToTry = 3;
        int nrTimesTried = 0;
        Socket slamSocket = null;
        boolean connectedWithSlam = false;

        do {
            log.info("Registering with SLAM " + config.getSlamIp() + ":" + config.getSlamPort());
            registeringWithSlam = true;
            try {
                slamSocket = new Socket();
                slamSocket.connect(new InetSocketAddress(config.getSlamIp(), config.getSlamPort()), 5000);
                log.info("Connected with SLAM");
                connectedWithSlam = true;
            } catch (Exception e) {
                log.error("Could not connect with the SLAM: " + e);
                try {
                    Thread.sleep(10 * 1000);
                } catch (InterruptedException e1) {
                    Thread.currentThread().interrupt();
                }
            }
        } while (!connectedWithSlam && ++nrTimesTried < maxNrTimesToTry);

        if (connectedWithSlam) {
            try (ObjectOutputStream oos = new ObjectOutputStream(slamSocket.getOutputStream());
                 ObjectInputStream ois = new ObjectInputStream(slamSocket.getInputStream())) {

                oos.writeByte(RapidMessages.AC_REGISTER_SLAM);
                oos.writeLong(myId);
                oos.writeInt(RapidConstants.OS.LINUX.ordinal());
                oos.flush();

                // Send the vmmId and vmmPort to the SLAM so it can start the VM
                oos.writeUTF(vmmIp);
                oos.writeInt(config.getVmmPort());

                // FIXME: should not use static values here.
                oos.writeInt(vmNrVCPUs); // send vcpuNum as int
                oos.writeInt(vmMemSize); // send memSize as int
                oos.writeInt(vmNrGpuCores); // send gpuCores as int
                oos.writeUTF(jsonQosParams);

                oos.flush();

                int response = ois.readByte();
                if (response == RapidMessages.OK) {
                    log.info("SLAM OK, getting the VM details");
                    String vmIp = ois.readUTF();

                    vm = new Clone("", vmIp);
                    vm.setId((int) myId);

                    // Save the userID, etc.
                    savePrevParameters();

                    // Save the VM for future references.
                    saveVm();

                    return true;
                } else if (response == RapidMessages.ERROR) {
                    log.error("SLAM registration replied with ERROR, VM will be null");
                } else {
                    log.error(
                            "SLAM registration replied with uknown message " + response + ", VM will be null");
                }
            } catch (IOException e) {
                log.error("Could not connect with the SLAM: " + e);
            }
        }

        registeringWithSlam = false;
        return false;
    }


    private static void savePrevParameters() {
        sharedPrefs.setProperty(Constants.USER_ID_KEY, Long.toString(myId));

        // The file containing preferences shared by all applications, like userID, etc.
        try {
            log.info("Saving properties in file: " + config.getSharedPrefsFile());
            FileOutputStream sharedPrefsOs = new FileOutputStream(config.getSharedPrefsFile());
            sharedPrefs.store(sharedPrefsOs, "Previous userID");
            // sharedPrefsOs.close();
            log.info("Finished saving properties in file");
        } catch (FileNotFoundException e) {
            log.error("Could not create or open the sharedPrefs file: " + e);
        } catch (IOException e) {
            log.error("Could not save the user parameters: " + e);
        }
    }

    private static void readPrevVm() {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(prevVmFilePath));) {

            vm = (Clone) ois.readObject();

        } catch (IOException | ClassNotFoundException e) {
            log.error("Could not read the VM: " + e);
        }
    }

    private static void saveVm() {
        try (ObjectOutputStream oos =
                     new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(prevVmFilePath)));) {

            oos.writeObject(vm);

        } catch (IOException e) {
            log.error("Could not store the VM: " + e);
        }
    }


    /**
     * <p>Read the xml file with the QoS parameters, which has been created by the RAPID Compiler.</p>
     * <p>The format of the file is this one:</p>
     *
     * <pre>
     *     {@code
     * <application>
    <name>TODO</name>
    <class>
    <name>/Users/sokol/Desktop/test/demo/JniTest.java</name>
    <method>
    <name>localjniCaller</name>
    <Remote>
    <computeIntensive>true</computeIntensive>
    </Remote>
    <QoS>
    <term>cpu</term>
    <operator>ge</operator>
    <threshold>1200</threshold>
    <term>ram</term>
    <operator>ge</operator>
    <threshold>500</threshold>
    </QoS>
    </method>
    </class>
    ...
     *
     *      }
     * </pre>
     *
     * @return The content of the xml file as a string.
     */
    private String readQosParams () {
        String qosParams = "";
        try {
            qosParams = Utils.readResourceFileAsString(AC_RM.class.getClassLoader(), Constants.QOS_FILENAME);
        } catch (IOException e) {
            log.info("Could not find QoS file - " + e);
        }

        return qosParams;
    }


    /**
     *
     * @param qosParams The string containing the xml QoS. We assume the QoS parameters are correct
     *                  (meaning that we do not perform error checking here, in terms of:
     *                  naming of QoS parameters, formatting of the QoS parameters, etc.).
     * @return The parsed string, converted then in json format:
     * {"QoS":[{"operator":"CPU_UTIL", "term":"LT", "threshold":60}, {"operator":"ram_util", "term":"lt", "threshold":1024}]}
     */
    private String parseQosParams(String qosParams) {
        StringBuilder jsonQosParams = new StringBuilder();
        jsonQosParams.append("{\"QoS\":[");
        List<String> terms = new LinkedList<>();
        List<String> operators = new LinkedList<>();
        List<String> thresholds = new LinkedList<>();

        // Remove all spaces and new lines
        qosParams = readQosParams().replace(" ", "").replace("\n", "");

        // Search for substrings starting with <QoS> and ending with </QoS>, excluding these tags
        Pattern qosPattern = Pattern.compile("(<QoS>(.*?)</QoS>)");
        Matcher qosMatcher = qosPattern.matcher(qosParams);

        Pattern termPattern = Pattern.compile("(<term>(.*?)</term>)");
        Pattern operatorPattern = Pattern.compile("(<operator>(.*?)</operator>)");
        Pattern thresholdPattern = Pattern.compile("(<threshold>(.*?)</threshold>)");

        while (qosMatcher.find()) {
            // Log.i(TAG, qosMatcher.group(2));
            // <term>cpu</term><operator>ge</operator><threshold>1500</threshold><term>ram</term><operator>ge</operator><threshold>1000</threshold>
            String qosString = qosMatcher.group(2);
            Matcher termMatcher = termPattern.matcher(qosString);
            Matcher operatorMatcher = operatorPattern.matcher(qosString);
            Matcher thresholdMatcher = thresholdPattern.matcher(qosString);

            while (termMatcher.find()) {
                terms.add(termMatcher.group(2));
            }

            while (operatorMatcher.find()) {
                operators.add(operatorMatcher.group(2));
            }

            while (thresholdMatcher.find()) {
                thresholds.add(thresholdMatcher.group(2));
            }
        }

        if (terms.size() != operators.size() || terms.size() != thresholds.size()) {
            log.info("QoS params not correctly formatted: number of terms, operators, and thresholds differ!");
        } else {
            for (int i = 0; i < terms.size(); i++) {
                jsonQosParams.append("{");
                jsonQosParams.append("\"term\":\"").append(terms.get(i)).append("\", ");
                jsonQosParams.append("\"operator\":\"").append(operators.get(i)).append("\", ");
                jsonQosParams.append("\"threshold\":");
                String th = thresholds.get(i);
                if (RapidUtils.isNumeric(th)) {
                    jsonQosParams.append(thresholds.get(i));
                } else {
                    jsonQosParams.append("\"").append(thresholds.get(i)).append("\"");
                }
                jsonQosParams.append("}");
                if (i < terms.size() - 1) {
                    jsonQosParams.append(", ");
                }
            }
        }

        jsonQosParams.append("]}");
        return jsonQosParams.toString();
    }

    /**
     * Handles the client requests, which are actually applications running on the same machine as the
     * AC_RM is running.
     *
     * @author sokol
     */
    private class ClientHandler implements Runnable {

        private Socket clientSocket;

        public ClientHandler(Socket clientSocket) {
            this.clientSocket = clientSocket;
        }

        @Override
        public void run() {
            // TODO Implement communication with the client, which is actually an application running on
            // the same machine. The application will ask for info about the VM to connect to.

            try (InputStream is = clientSocket.getInputStream();
                 OutputStream os = clientSocket.getOutputStream();
                 ObjectInputStream ois = new ObjectInputStream(is);
                 ObjectOutputStream oos = new ObjectOutputStream(os)) {

                int command;
                do {
                    command = is.read();
                    log.info("Received command from app: " + command);

                    switch (command) {
                        case RapidMessages.AC_HELLO_AC_RM:
                            log.info("An app is asking for VM info ------");
                            oos.writeLong(myId);
                            oos.writeObject(vm);

                            oos.flush();
                            break;
                    }
                } while (command != -1);

            } catch (IOException e) {
                log.error("Error talking to the client (which is an app runnig on the same machine): " + e);
            } finally {
                try {
                    clientSocket.close();
                    log.info("Communication closed with client");
                } catch (IOException e) {
                    log.error("Error while closing the socket: " + e);
                }
            }
        }
    }

    public static void main(String[] args) {
        log.info("Starting the AC_RM server");
        AC_RM acRm = new AC_RM();

        try (ServerSocket serverSocket = new ServerSocket(config.getAcRmPort())) {
            log.info("Started server on port " + config.getAcRmPort());

            // If it didn't throw an exception it means that this is the first instance of the AC_RM.
            // This is responsible for registering with the DS and SLAM.
            registrationScheduledPool.scheduleWithFixedDelay(
                    new Runnable() {
                        @Override
                        public void run() {
                            if (vm == null) {
                                log.info("We do not have a VM, registering with the DS and SLAM...");
                                if (registeringWithDs || registeringWithSlam) {
                                    log.info("Registration already in progress...");
                                } else {
                                    registerWithDsAndSlam();
                                }
                            }
                        }
                    }, FREQUENCY_REGISTRATION, FREQUENCY_REGISTRATION, TimeUnit.MILLISECONDS
            );

            registerWithDsAndSlam();

            while (true) {
                Socket clientSocket = serverSocket.accept();
                log.info("New client connected");
                acRm.handleNewClient(clientSocket);
            }

            // else VM is null, so when we register with the DS we'll get a new one.
        } catch (IOException e) {
            if (e instanceof java.net.BindException) {
                // If this exception is thrown, it means that the port is already in use by a previous
                // instance of the AC_RM.
                // The client should just connect to the listening server in that case.
                log.warn("AC_RM may be already running: " + e);
            } else {
                e.printStackTrace();
            }
        } finally {
            if (registrationScheduledPool != null) {
                registrationScheduledPool.shutdown();
                log.info("The registrationScheduledPool is now shut down");
            }
        }
    }
}
