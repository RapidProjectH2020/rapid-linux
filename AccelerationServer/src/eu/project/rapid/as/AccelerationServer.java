package eu.project.rapid.as;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
// import org.bouncycastle.jce.provider.BouncyCastleProvider;

import eu.project.rapid.common.RapidConstants.REGIME;
import eu.project.rapid.common.RapidMessages;
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
  private int userId = -1;

  public AccelerationServer() {
    log.info("Starting the AS 9/14/2016 12:14");
    config = new Configuration(AccelerationServer.class.getSimpleName(), REGIME.AS);

    if (config.getSlamIp() == null) {
      log.warn("The configuration file does not contain the SLAM IP.");
      quit("Not possible to register the AS with the SLAM.");
    }

    if (config.getDsIp() == null) {
      log.warn("The configuration file does not contain the DSE IP.");
      quit("Not possible to register the AS with the DSE.");
    }

    try {
      Utils.createDirIfNotExist(config.getRapidFolder());
      Utils.createOffloadFile();
    } catch (FileNotFoundException e) {
      log.error("Could not initialize the RAPID folder on " + config.getUserHomeFolder());
    }

    // FIXME
    // register();

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

  /**
   * 1. Register to the VMM.<br>
   * 2. If the registration with the VMM was OK, register with the DSE, otherwise quit.<br>
   * 
   * @return
   */
  private void register() {
    if (!registerToVMM()) {
      quit("Error while registering.");
    } else {
      log.info("Correctly registered.");
    }
  }

  private boolean registerToVMM() {

    try (Socket vmmSocket = new Socket(config.getSlamIp(), config.getSlamPort());
        ObjectOutputStream oos = new ObjectOutputStream(vmmSocket.getOutputStream());
        ObjectInputStream ois = new ObjectInputStream(vmmSocket.getInputStream())) {

      oos.writeByte(RapidMessages.AS_RM_REGISTER_VMM);
      oos.flush();
      userId = ois.readInt();

      // If the registration with the DS is not performed correctly, the AS will quit.
      if (registerToDs()) {
        // Otherwise the registration with the DS was OK.
        oos.write(RapidMessages.OK);
        return true;
      } else {
        oos.write(RapidMessages.ERROR);
      }
      oos.flush();
    } catch (IOException e) {
      log.error("Error while connecting to VMM: " + e);
    }

    return false;
  }

  private boolean registerToDs() {
    try (Socket dsSocket = new Socket(config.getDsIp(), config.getDsPort());
        ObjectOutputStream oos = new ObjectOutputStream(dsSocket.getOutputStream());
        ObjectInputStream ois = new ObjectInputStream(dsSocket.getInputStream())) {

      oos.writeByte(RapidMessages.AS_RM_REGISTER_DS);
      oos.flush();
      int response = ois.readByte();
      if (response == RapidMessages.OK) {
        return true;
      } else if (response == RapidMessages.ERROR) {
        log.error("DS replied with ERROR to the register request");
      } else {
        log.error("DS replied with unkown message to the register request");
      }
    } catch (IOException e) {
      log.error("Not possible to connect to DS: " + e);
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
  public int getUserId() {
    return userId;
  }

  /**
   * @param userId the userId to set
   */
  public void setUserId(int userId) {
    this.userId = userId;
  }

  public static void main(String[] argv) {
    new AccelerationServer();
  }
}
