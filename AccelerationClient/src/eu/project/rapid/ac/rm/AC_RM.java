package eu.project.rapid.ac.rm;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import eu.project.rapid.common.Clone;
import eu.project.rapid.common.RapidConstants;
import eu.project.rapid.common.RapidConstants.REGIME;
import eu.project.rapid.common.RapidMessages;
import eu.project.rapid.utils.Configuration;
import eu.project.rapid.utils.Constants;

/**
 * This class is the common component of the AC project. It takes care of registering with the DS.
 * Is started by the first application that runs on the machine. When this class is started the
 * first time it launches a server on the given port so the next application that tries to start the
 * AC_RM again will fail. The AC_RM will register the client with the DS according to user
 * preferences (build a GUI or through preference files). In particular, the AC_RM will choose to
 * connect to the previous VM or to ask a new VM.
 * 
 * @author sokol
 *
 */
public class AC_RM {

  private static final Logger log = LogManager.getLogger(AC_RM.class.getSimpleName());

  private static Configuration config;
  private Properties sharedPrefs;
  private Clone vm;
  // private String slamIp;
  private long myId = -1;
  private final String prevVmFileName = "prevVm.ser"; // The file where the VM will be stored for
                                                      // future use.
  private String prevVmFilePath; // The full path of the file where the VM will
                                 // be stored for future use.
  private static boolean registerAsPrev;

  private ArrayList<String> vmmIPs;
  private static final int vmNrVCPUs = 1; // FIXME: number of CPUs on the VM
  private static final int vmMemSize = 512; // FIXME
  private static final int vmNrGpuCores = 1200; // FIXME

  public AC_RM() {
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

    if (config.isConnectToPrevVm()) {
      // Read the previously saved VM. If there is no previously saved VM, then the VM object
      // still remains null, so we'll ask for a new one when registering with the DS.
      readPrevVm();
    }
  }

  private void handleNewClient(Socket clientSocket) {
    new Thread(new ClientHandler(clientSocket)).start();
  }

  private boolean registerWithDsAndSlam() {

    boolean registeredWithSlam = false;

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

    return registeredWithSlam;
  }

  /**
   * Register to the DS.<br>
   * 
   * If the VM is null then ask for a list of SLAMs that can provide a VM. Otherwise notify the DS
   * that we want to connect to the previous VM.
   * 
   * @throws IOException
   * @throws UnknownHostException
   * @throws ClassNotFoundException
   */
  @SuppressWarnings("unchecked")
  private boolean registerWithDs() {
    log.info("Registering with DS " + config.getDsIp() + ":" + config.getDsPort());
    try (Socket dsSocket = new Socket(config.getDsIp(), config.getDsPort());
        ObjectOutputStream dsOut = new ObjectOutputStream(dsSocket.getOutputStream());
        ObjectInputStream dsIn = new ObjectInputStream(dsSocket.getInputStream())) {

      if (!registerAsPrev) { // Get a new VM
        log.info("Registering as NEW with ID:" + myId + " with the DS...");
        dsOut.writeByte(RapidMessages.AC_REGISTER_NEW_DS);
        dsOut.writeLong(myId);
      } else { // Register and ask for the previous VM
        log.info("Registering as PREV with ID: " + myId + " with the DS...");
        dsOut.writeByte(RapidMessages.AC_REGISTER_PREV_DS);
        dsOut.writeLong(myId);
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

    return false;
  }

  private void savePrevParameters() {
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

  private void readPrevVm() {
    try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(prevVmFilePath));) {

      vm = (Clone) ois.readObject();

    } catch (IOException | ClassNotFoundException e) {
      log.error("Could not read the VM: " + e);
    }
  }

  private void saveVm() {
    try (ObjectOutputStream oos =
        new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(prevVmFilePath)));) {

      oos.writeObject(vm);

    } catch (IOException e) {
      log.error("Could not store the VM: " + e);
    }
  }

  // private void chooseBestSlam(List<String> slamIPs) {
  // // FIXME Currently just choose the first one.
  // log.info("Choosing the best SLAM from the list...");
  // if (slamIPs == null || slamIPs.size() == 0) {
  // throw new NoSuchElementException("Exptected at least one SLAM, don't know how to proceed!");
  // } else {
  // Iterator<String> ipListIterator = slamIPs.iterator();
  // log.info("Received SLAM IP List: ");
  // while (ipListIterator.hasNext()) {
  // log.info(ipListIterator.next());
  // }
  //
  // config.setSlamIp(slamIPs.get(0));
  // }
  // }

  /**
   * @param vmmIp The IP of the VMM to ask for a VM.
   * @throws IOException
   * @throws UnknownHostException
   */
  private boolean registerWithSlam(String vmmIp) {
    log.info("Registering with SLAM " + config.getSlamIp() + ":" + config.getSlamPort());
    try (Socket socket = new Socket(config.getSlamIp(), config.getSlamPort());
        ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
        ObjectInputStream ois = new ObjectInputStream(socket.getInputStream())) {

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

    return false;
  }

  /**
   * Handles the client requests, which are actually applications running on the same machine as the
   * AC_RM is running.
   * 
   * @author sokol
   *
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

        int command = -1;
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
      // This is responsible for registering with the DS.

      boolean registered = false;
      long waitToRegister = 2000; // ms
      int timesTriedRegistering = 0;
      do {
        registered = acRm.registerWithDsAndSlam();
        timesTriedRegistering++;

        if (!registered) {
          log.info("Could not register with DS and SLAM, trying after " + waitToRegister + " ms");
          try {
            Thread.sleep(waitToRegister);
          } catch (InterruptedException e) {
          }

          if (timesTriedRegistering >= 3) {
            if (registerAsPrev) {
              registerAsPrev = false;
              timesTriedRegistering = 0;
            } else {
              waitToRegister *= 2;
            }
          }
        }
      } while (!registered);

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
        log.warn("AC_RM is already running: " + e);
      } else {
        e.printStackTrace();
      }
    }
  }
}
