package eu.project.rapid.as;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
// import org.bouncycastle.jce.provider.BouncyCastleProvider;

import eu.project.rapid.common.RapidConstants.REGIME;
import eu.project.rapid.common.RapidMessages;
import eu.project.rapid.common.RapidUtils;
import eu.project.rapid.utils.Configuration;
import eu.project.rapid.utils.Utils;

/**
 * Implementation of the Acceleration Server (AS), which starts running automatically when the
 * device is booted.<br>
 * The AS reads the configuration file and tries to registers to the Directory Server (DS).
 */
public class AccelerationServer {
  //
  // static {
  // Security.insertProviderAt(new BouncyCastleProvider(), 1);
  // }

  private static final Logger log = LogManager.getLogger(AccelerationServer.class.getSimpleName());
  private Configuration config;

  // The ID of the user that is requesting this VM.
  private long userId = -1; // The userId will be given by the VMM
  private int vmId = -1; // The vmId will be assigned by the DS
  private String vmIp; // The vmIp should be extracted by us

  public AccelerationServer() {
    log.info("Starting the AS 15/09/2016 10:39");
    config = new Configuration(AccelerationServer.class.getSimpleName(), REGIME.AS);

    if (config.getSlamIp() == null) {
      log.warn("The configuration file does not contain the SLAM IP.");
      quit("Not possible to register the AS with the SLAM.");
    }

    if (config.getDsIp() == null) {
      log.warn("The configuration file does not contain the DSE IP.");
      quit("Not possible to register the AS with the DS.");
    }

    try {
      Utils.createDirIfNotExist(config.getRapidFolder());
      Utils.createDirIfNotExist(config.getRapidFolder() + File.separator + "libs");
      Utils.createOffloadFile();
    } catch (FileNotFoundException e) {
      log.error("Could not initialize the RAPID folder on " + config.getUserHomeFolder());
    }

    // FIXME
    waitForNetworkToBeUp();
    vmIp = RapidUtils.getVmIpLinux();
    log.info("My IP: " + vmIp);
    log.info("My ID: " + vmId);
    if (!registerWithVmmAndDs()) {
      quit("Error while registering, exiting...");
    }

    // Start the thread that listens for network connectivity measurements
    log.info("Starting NetworkProfiler thread...");
    new Thread(new NetworkProfilerServer(config)).start();

    // Start the clear AS server thread
    log.info("Starting CLEAR thread...");
    new Thread(new ClientListenerClear(config)).start();

    if (config.isCryptoInitialized()) {
      log.info("Starting SSL thread...");
      new Thread(new ClientListenerSSL(config)).start();
    } else {
      log.warn("SSL thread not started since crypto not initialized");
    }
  }

  private void waitForNetworkToBeUp() {
    boolean hostMachineReachable = false;
    do {
      try {
        // The VM runs on the host machine, so checking if we can ping the vmmIP in
        // reality we are checking if we can ping the host machine.
        // We should definitely be able to do that, otherwise this clone is useless if not
        // connected to the network.

        InetAddress hostMachineAddress = InetAddress.getByName(config.getVmmIp());
        try {
          log.info(
              "Trying to ping the host machine " + hostMachineAddress.getHostAddress() + "...");
          hostMachineReachable = hostMachineAddress.isReachable(1000);
          try {
            Thread.sleep(1 * 1000);
          } catch (InterruptedException e) {
          }
        } catch (IOException e) {
          log.warn("Error while trying to ping the host machine: " + e);
        }
      } catch (UnknownHostException e1) {
        log.error("Error while getting hostname: " + e1);
      }
    } while (!hostMachineReachable);
    log.info("Host machine replied to ping. Network interface is up and running.");
  }

  /**
   * 1. Register to the VMM.<br>
   * 2. If the registration with the VMM was OK, register with the DS, otherwise quit.<br>
   * 
   * @return
   */
  private boolean registerWithVmmAndDs() {

    try (Socket vmmSocket = new Socket(config.getVmmIp(), config.getVmmPort());
        ObjectOutputStream vmmOs = new ObjectOutputStream(vmmSocket.getOutputStream());
        ObjectInputStream vmmIs = new ObjectInputStream(vmmSocket.getInputStream())) {

      log.info("Registering with the VMM " + config.getVmmIp() + ":" + config.getVmmPort() + "...");
      vmmOs.writeByte(RapidMessages.AS_RM_REGISTER_VMM);
      vmmOs.writeUTF(vmIp);
      vmmOs.flush();

      // Receive message format: status (java byte), userId (java long)
      byte status = vmmIs.readByte();
      System.out.println("VMM return Status: " + (status == RapidMessages.OK ? "OK" : "ERROR"));
      if (status == RapidMessages.OK) {
        userId = vmmIs.readLong();
        System.out.println("userId is: " + userId);

        if (registerWithDs()) {
          // Notify the VMM that the registration with the DS was correct
          System.out.println("Correctly registered with the DS");
          vmmOs.writeByte(RapidMessages.OK);
          vmmOs.flush();
          return true;
        } else {
          // Notify the VMM that the registration with the DS was correct
          System.out.println("Registration with the DS failed");
          vmmOs.writeByte(RapidMessages.ERROR);
          vmmOs.flush();
          return false;
        }
      }
    } catch (IOException e) {
      log.info("Could not register with the VMM: " + e);
    }

    return false;
  }

  private boolean registerWithDs() {
    try (Socket dsSocket = new Socket(config.getDsIp(), config.getDsPort());
        ObjectOutputStream oos = new ObjectOutputStream(dsSocket.getOutputStream());
        ObjectInputStream ois = new ObjectInputStream(dsSocket.getInputStream())) {

      log.info("Registering with the DS: " + config.getDsIp() + ":" + config.getDsPort() + "...");
      oos.writeByte(RapidMessages.AS_RM_REGISTER_DS);
      oos.writeLong(userId); // userId
      oos.flush();
      int response = ois.readByte();
      if (response == RapidMessages.OK) {
        return true;
      } else if (response == RapidMessages.ERROR) {
        log.info("DS replied with ERROR to the register request");
      } else {
        log.info("DS replied with unkown message to the register request");
      }
    } catch (IOException e) {
      log.info("Could not register with the DS: " + e);
    }
    return false;
  }

  private void quit(String message) {
    log.warn(message + " - Quiting.");
    System.exit(1);
  }

  public Configuration getConfig() {
    return config;
  }


  public void setConfig(Configuration config) {
    this.config = config;
  }

  /**
   * @return the userId
   */
  public long getUserId() {
    return userId;
  }

  /**
   * @param userId the userId to set
   */
  public void setUserId(long userId) {
    this.userId = userId;
  }

  public static void main(String[] argv) {
    new AccelerationServer();
  }
}
