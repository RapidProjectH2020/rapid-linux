package eu.project.rapid.ac;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.security.CodeSource;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.net.ssl.SSLSocket;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import eu.project.rapid.ac.profilers.NetworkProfiler;
import eu.project.rapid.ac.rm.AC_RM;
import eu.project.rapid.common.Commands;
import eu.project.rapid.common.Configuration;
import eu.project.rapid.common.Constants;
import eu.project.rapid.common.Constants.ExecLocation;
import eu.project.rapid.common.ResultContainer;
import eu.project.rapid.common.Utils;
import eu.project.rapid.common.VM;

/**
 * The class that handles the task execution using Java reflection.<br>
 * 
 * @author sokol
 *
 */
public class DFE {

  // static {
  // Security.insertProviderAt(new BouncyCastleProvider(), 1);
  // }

  private final static Logger log = LogManager.getLogger(DFE.class.getSimpleName());

  private Configuration config;
  private DSE dse;

  // Variables related to the app using the DFE
  private String jarFilePath;
  private String jarName; // The jar name without ".jar" extension
  private long jarSize;
  private int userID;
  private int nrVMs = 1;
  private boolean connectedWithAs;

  // Socket and streams with the VM
  private VM vm;
  private Socket vmSocket;
  private InputStream vmIs;
  private OutputStream vmOs;
  private ObjectOutputStream vmOos;
  private ObjectInputStream vmOis;

  // Profilers
  private NetworkProfiler netProfiler;

  // Constructor to be used by the AS
  public DFE(boolean serverSide) {}

  // Constructor to be used by the applications.
  public DFE() {
    config = new Configuration(DFE.class.getSimpleName());
    dse = new DSE();

    // Create the folder where the client apps wills keep their data.
    try {
      Utils.createDirIfNotExist(config.getRapidClientFolder());
    } catch (FileNotFoundException e) {
      log.error("Could not create folder " + config.getRapidClientFolder() + ": " + e);
    }

    startAcRm();
    initialize();

    if (vm == null) {
      log.warn("It was not possible to get a VM, only local execution will be possible.");
    } else {
      log.info("Received VM " + vm + " from AC_RM, connecting now...");

      if (config.isConnectSsl()) {
        connectedWithAs = connectWitAsSsl();
        if (!connectedWithAs) {
          log.error("It was not possible to connect with the VM using SSL, connecting in clear...");
          connectedWithAs = connectWitAs();
        }
      } else {
        connectedWithAs = connectWitAs();
      }

      if (connectedWithAs) {
        log.info("Connected to VM");
        netProfiler = new NetworkProfiler(vmIs, vmOs);
        NetworkProfiler.rttPing();

        registerWithAs();
      }
    }
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
      vmSocket = (SSLSocket) config.getSslFactory().createSocket(vm.getIp(), vm.getSslPort());

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
    // Get the name of the jar file of this app. It will be needed so that the jar can be sent to
    // the AS for offload executions.
    CodeSource codeSource = DFE.class.getProtectionDomain().getCodeSource();
    if (codeSource != null) {
      jarFilePath = codeSource.getLocation().getFile();// getPath();
      // jarFilePath = "/Users/sokol/Desktop/demo.jar";
      File jarFile = new File(jarFilePath);
      jarSize = jarFile.length();
      log.info("jarFilePath: " + jarFilePath + ", jarSize: " + jarSize + " bytes");
      jarName = jarFilePath.substring(jarFilePath.lastIndexOf(File.separator) + 1,
          jarFilePath.lastIndexOf("."));
      try {
        vmOs.write(Commands.AC_REGISTER_AS);
        vmOos.writeUTF(jarName);
        vmOos.writeLong(jarSize);
        vmOos.flush();
        int response = vmIs.read();
        if (response == Commands.AS_APP_PRESENT_AC) {
          log.info("The app is already present on the AS, do nothing.");
        } else if (response == Commands.AS_APP_REQ_AC) {
          log.info("Should send the app to the AS");
          sendApp(jarFile);
        }
      } catch (IOException e) {
        log.error("Could not send message to VM: " + e);
      }
    } else {
      log.warn("Could not get the CodeSource object needed to get the executable of the app");
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
      int read = 0;
      while ((read = fis.read(buffer)) > -1) {
        vmOs.write(buffer, 0, read);
        totalRead += read;
      }

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
    if (vmOis != null) {
      try {
        vmOis.close();
        vmOos.close();
      } catch (IOException e) {
        log.error("Error while closing vmStreams: " + e);
      }
    }
    if (vmSocket != null) {
      try {
        vmSocket.close();
      } catch (IOException e) {
        log.error("Error while closing vmSocket: " + e);
      }
    }
  }

  /**
   * Initialize the variables that were stored by previous executions, e.g. the userID, the VM to
   * connect to, etc. Usually these variables will be provided by the AC_RM.
   */
  private void initialize() {
    // Connect to the AC_RM, which is a server component running on this machine, and ask for the
    // parameters.

    boolean connectionAcRmSuccess = false;
    do {
      try (Socket socket = new Socket(InetAddress.getLocalHost(), config.getAcRmPort());
          OutputStream os = socket.getOutputStream();
          InputStream is = socket.getInputStream();
          ObjectOutputStream oos = new ObjectOutputStream(os);
          ObjectInputStream ois = new ObjectInputStream(is);) {

        // Ask the AC_RM for the VM to connect to.
        os.write(Commands.AC_HELLO_AC_RM);
        userID = ois.readInt();
        try {
          vm = (VM) ois.readObject();
        } catch (ClassNotFoundException e) {
          log.error("Could not properly receive the VM info from the AC_RM: " + e);
        }

        log.info("Finished talking to AC_RM, received userID=" + userID + " and VM=" + vm);
        connectionAcRmSuccess = true;
      } catch (IOException e) {
        log.warn("AC_RM is not started yet, retrying after 2 seconds");
        try {
          Thread.sleep(2 * 1000);
        } catch (InterruptedException e1) {
        }
      }
    } while (!connectionAcRmSuccess);
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

    } catch (IOException e1) {
      // TODO Auto-generated catch block
      e1.printStackTrace();
    }
  }

  private class StreamThread extends Thread {

    private InputStream is;

    public StreamThread(InputStream stream) {
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
    ExecLocation execLocation = dse.findExecLocation(jarName, m.getName());
    ExecutorService executor = Executors.newFixedThreadPool(2);

    switch (execLocation) {
      case HYBRID:
        log.error("Hybrid execution not implemented yet");
        break;

      default:
        Future<Object> futureTotalResult =
            executor.submit(new TaskRunner(execLocation, m, values, o));
        try {
          result = futureTotalResult.get();
        } catch (InterruptedException | ExecutionException e) {
          log.error("Error while calling TaskRunner: " + e);
        }
        break;
    }

    executor.shutdown();
    return result;

  }

  private class TaskRunner implements Callable<Object> {
    private ExecLocation execLocation;
    private Method m;
    private Object[] pValues;
    private Object o;
    private Object result;

    public TaskRunner(ExecLocation execLocation, Method m, Object[] pValues, Object o) {
      this.execLocation = execLocation;
      this.m = m;
      this.pValues = pValues;
      this.o = o;
    }

    @Override
    public Object call() throws Exception {
      if (execLocation == ExecLocation.LOCAL) {
        try {
          result = executeLocally(m, pValues, o);
        } catch (IllegalArgumentException | IllegalAccessException | InvocationTargetException e) {
          log.error("Error while calling executeLocally: " + e);
          e.printStackTrace();
        }
      } else if (execLocation == ExecLocation.REMOTE) {
        try {
          result = executeRemotely(m, pValues, o);
          if (result instanceof InvocationTargetException) {
            // The remote execution throwed an exception, try to run the method locally.
            log.warn(
                "The returned result was InvocationTargetException. Running the method locally");
            result = executeLocally(m, pValues, o);
          }
        } catch (IllegalArgumentException | SecurityException | IllegalAccessException
            | InvocationTargetException | ClassNotFoundException | NoSuchMethodException e) {
          log.error("Error while calling executeRemotely: " + e);
          e.printStackTrace();
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

      // ProgramProfiler progProfiler = new ProgramProfiler(mAppName, m.getName());
      // DeviceProfiler devProfiler = new DeviceProfiler(mContext);
      // NetworkProfiler netProfiler = null;
      // Profiler profiler = new Profiler(mRegime, mContext, progProfiler, netProfiler,
      // devProfiler);

      // Start tracking execution statistics for the method
      // profiler.startExecutionInfoTracking();

      // Make sure that the method is accessible
      Object result = null;
      long startTime = System.nanoTime();
      m.setAccessible(true);
      result = m.invoke(o, pValues); // Access it
      long mPureLocalDuration = System.nanoTime() - startTime;
      log.info("LOCAL " + m.getName() + ": Actual Invocation duration - "
          + mPureLocalDuration / Constants.MEGA + "ms");

      // Collect execution statistics
      // profiler.stopAndLogExecutionInfoTracking(prepareDataDuration, mPureLocalDuration);
      // lastLogRecord = profiler.lastLogRecord;

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
     */
    private Object executeRemotely(Method m, Object[] pValues, Object o)
        throws IllegalArgumentException, IllegalAccessException, InvocationTargetException,
        SecurityException, ClassNotFoundException, NoSuchMethodException {
      Object result = null;

      // ProgramProfiler progProfiler = new ProgramProfiler(mAppName, m.getName());
      // DeviceProfiler devProfiler = new DeviceProfiler(mContext);
      // NetworkProfiler netProfiler = new NetworkProfiler();
      // Profiler profiler = new Profiler(mRegime, mContext, progProfiler, netProfiler,
      // devProfiler);

      // Start tracking execution statistics for the method
      // profiler.startExecutionInfoTracking();

      try {
        long startTime = System.nanoTime();
        vmOs.write(Commands.AC_OFFLOAD_REQ_AS);
        result = sendAndExecute(m, pValues, o);

        long remoteDuration = System.nanoTime() - startTime;
        log.info("REMOTE " + m.getName() + ": Actual Send-Receive duration - "
            + remoteDuration / Constants.MEGA + "ms");
        // Collect execution statistics
        // profiler.stopAndLogExecutionInfoTracking(prepareDataDuration, mPureRemoteDuration);
        // lastLogRecord = profiler.lastLogRecord;
      } catch (Exception e) {
        // No such host exists, execute locally
        log.error("REMOTE ERROR: " + m.getName() + ": " + e);
        e.printStackTrace();
        result = executeLocally(m, pValues, o);
        // ConnectionRepair repair = new ConnectionRepair();
        // repair.start();
      }

      return result;
    }

    /**
     * Send the object, the method to be executed and parameter values to the remote server for
     * execution.
     * 
     * @param m method to be executed
     * @param pValues parameter values of the remoted method
     * @param o the remoted object
     * @param objIn ObjectInputStream which to read results from
     * @param objOut ObjectOutputStream which to write the data to
     * @return result of the remoted method or an exception that occurs during execution
     * @throws IOException
     * @throws ClassNotFoundException
     * @throws NoSuchMethodException
     * @throws InvocationTargetException
     * @throws IllegalAccessException
     * @throws SecurityException
     * @throws IllegalArgumentException
     */
    private Object sendAndExecute(Method m, Object[] pValues, Object o)
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
      Object result;

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

      result = container.functionResult;
      long mPureRemoteDuration = container.pureExecutionDuration;

      // Estimate the perceived bandwidth
      // NetworkProfiler.addNewUlRateEstimate(totalTxBytesObject, container.getObjectDuration);

      log.info("Finished remote execution");

      return result;
    }

    /**
     * Send the object (along with method and parameters) to the remote server for execution
     * 
     * @param o
     * @param m
     * @param pValues
     * @param objOut
     * @throws IOException
     */
    private void sendObject(Object o, Method m, Object[] pValues) throws IOException {
      vmOos.reset();
      log.info("Write Object and data");

      // Send the number of VMs needed to execute the method
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
}
