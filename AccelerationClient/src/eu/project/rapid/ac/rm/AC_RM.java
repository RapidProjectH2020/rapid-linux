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
import java.util.List;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import eu.project.rapid.common.Clone;
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

  private Configuration config;
  private Properties sharedPrefs;
  private FileOutputStream sharedPrefsOs;
  private Clone vm;
  private int userID = -1;
  private final String prevVmFileName = "prevVm.ser"; // The file where the VM will be stored for
                                                      // future use.
  private String prevVmFilePath; // The full path of the file where the VM will
                                 // be stored for future use.

  public AC_RM() {
    // Read the configuration file to know the DS IP, the DS Port, and the port where the AC_RM
    // server should listen.
    config = new Configuration(AC_RM.class.getSimpleName(), REGIME.AC);
    prevVmFilePath = config.getRapidFolder() + File.separator + prevVmFileName;

    // The file containing preferences shared by all applications, like userID, etc.
    try {
      sharedPrefsOs = new FileOutputStream(config.getSharedPrefsFile());
    } catch (FileNotFoundException e) {
      log.error("Could not create or open the sharedPrefs file: " + e);
    }
    sharedPrefs = new Properties();

    try (ServerSocket serverSocket = new ServerSocket(config.getAcRmPort())) {
      log.info("Started server on port " + config.getAcRmPort());

      // If it didn't throw an exception it means that this is the first instance of the AC_RM.
      // This is responsible for registering with the DS.

      // Read previously saved information, like userID, etc.
      userID = Integer
          .parseInt(sharedPrefs.getProperty(Constants.USER_ID_KEY, Constants.USER_ID_DEFAULT));
      if (config.isConnectToPrevVm()) {
        // Read the previously saved VM. If there is no previously saved VM, then the VM object
        // still remains null, so we'll ask for a new one when registering with the DS.
        readPrevVm();
      }
      // else VM is null, so when we register with the DS we'll get a new one.

      // registerToDs();

      while (true) {
        Socket clientSocket = serverSocket.accept();
        log.info("New client connected");
        new Thread(new ClientHandler(clientSocket)).start();
      }
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

  /**
   * Register to the DS.<br>
   * 
   * If the VM is null then ask for a list of SLAMs that can provide a VM. Otherwise notify the DS
   * that we want to connect to the previous VM.
   */
  private void registerToDs() {

    try (Socket dsSocket = new Socket(config.getDsIp(), config.getDsPort());
        ObjectOutputStream oos = new ObjectOutputStream(dsSocket.getOutputStream());
        ObjectInputStream ois = new ObjectInputStream(dsSocket.getInputStream())) {

      if (vm == null) {
        oos.writeByte(RapidMessages.AC_REGISTER_NEW_DS);
        oos.writeInt(userID);
        oos.flush();

        userID = ois.readInt();

        // Receive a list of SLAM IPs and ports [<ip1, port1>, <ip2, port2>, ...]
        try {
          @SuppressWarnings("unchecked")
          List<String> slamIps = (List<String>) ois.readObject();
          @SuppressWarnings("unchecked")
          List<Integer> slamPorts = (List<Integer>) ois.readObject();
          assert (slamIps.size() > 0 && slamIps.size() == slamPorts.size());

          // Choose the best SLAM from the received ones and ask for a new VM.
          int bestSlamId = chooseBestSlam(slamIps, slamPorts);

          // Connect to the chosen SLAM to ask for a VM
          registerToSlam(slamIps.get(bestSlamId), slamPorts.get(bestSlamId));
        } catch (ClassNotFoundException e) {
          log.error("Error while receiving the SLAM IPs and Ports: " + e);
        }

      } else {
        // Register to previous VM
        oos.writeByte(RapidMessages.AC_REGISTER_PREV_DS);
        oos.writeInt(userID);
        oos.flush();

        userID = ois.readInt();

        // Read the details of the previous VM (we get these details again, in case the VM was
        // restarted and has a new IP for example.)
        int vmId = ois.readInt();
        String vmIp = ois.readUTF();
        int vmPort = ois.readInt();
        int vmSslPort = ois.readInt();
        vm = new Clone("", vmIp, vmPort, vmSslPort);
        vm.setId(vmId);
      }

      // Save the userID, etc.
      savePrevParameters();

      // Save the VM for future references.
      savePrevVm();

    } catch (UnknownHostException e) {
      log.error("Could not connect to the DS: " + e);
    } catch (IOException e) {
      log.error("Could not connect to the DS: " + e);
    }
  }

  private void savePrevParameters() {
    sharedPrefs.put(Constants.USER_ID_KEY, userID);
    try {
      sharedPrefs.store(sharedPrefsOs, "Previous userID");
      sharedPrefsOs.close();
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

  private void savePrevVm() {
    try (ObjectOutputStream oos =
        new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(prevVmFilePath)));) {

      oos.writeObject(vm);

    } catch (IOException e) {
      log.error("Could not store the VM: " + e);
    }
  }

  private int chooseBestSlam(List<String> slamIps, List<Integer> slamPorts) {
    int bestSlamIndex = 0;
    for (int i = 0; i < slamIps.size(); i++) {
      // TODO connect to the SLAM, measure some network metrics, and ask about the free resources.
      // Then choose the one with the best network connectivity and more resources.
    }

    return bestSlamIndex;
  }

  private void registerToSlam(String slamIp, int slamPort) {
    try (Socket socket = new Socket(slamIp, slamPort)) {

      ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
      ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
      oos.writeInt(RapidMessages.AC_REGISTER_SLAM);
      oos.writeInt(userID);
      // TODO send the QoS parameters
      oos.flush();

      int response = ois.readInt();
      if (response == RapidMessages.OK) {
        log.info("SLAM OK, getting the VM details");
        int vmId = ois.readInt();
        String vmIp = ois.readUTF();
        int vmPort = ois.readInt();
        int vmSslPort = ois.readInt();

        vm = new Clone("", vmIp, vmPort, vmSslPort);
        vm.setId(vmId);
      } else if (response == RapidMessages.ERROR) {
        log.error("SLAM registration replied with ERROR, VM will be null");
      } else {
        log.error("SLAM registration replied with uknown message, VM will be null");
      }

    } catch (IOException e) {
      log.error("Could not connect to SLAM for registering: " + e);
    }
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
              oos.writeInt(userID);

              // FIXME: For the moment I send a temporary VM since the DS and SLAM component are not
              // yet implemented.
              // VM tempVm = new VM(1, InetAddress.getLocalHost().getHostAddress(),
              // config.getAsPort(),
              Clone tempVm = new Clone("", "10.0.0.3", config.getAsPort(), config.getAsPortSsl());
              tempVm.setId(1);
              oos.writeObject(tempVm);

              // oos.writeObject(vm);
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
    new AC_RM();
  }
}
