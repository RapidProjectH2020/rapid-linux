package eu.project.rapid.ac.profilers;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import eu.project.rapid.ac.db.DB;
import eu.project.rapid.ac.db.DBCache;
import eu.project.rapid.ac.db.DBEntry;
import eu.project.rapid.utils.Configuration;

public class LogRecord {
  private String logFilePath;
  // private DB db;

  public String appName;
  public String methodName;
  public String execLocation;
  public long prepareDataDuration;
  public long execDuration;
  public long pureDuration;

  public Long threadCpuTime;
  public int instructionCount;
  public int methodCount;
  public int threadAllocSize;
  public int threadGcInvocationCount;
  public int globalGcInvocationCount;

  private String networkType;
  private String networkSubType;
  private int rtt = -1;
  private int ulRate = -1;
  private int dlRate = -1;
  private long rxBytes = -1;
  private long txBytes = -1;

  private long logRecordTime;

  public static final String LOG_HEADERS =
      "#AppName,MethodName,ExecLocation,PrepareDataDuration,ExecDuration,PureDuration,"
          + "ThreadCpuTime,InstructionCount,MethodCount,ThreadAllocSize,ThreadGcInvocCount,GlobalGcInvocCount,"
          + "NetType,NetSubtype,RTT,UlRate,DlRate,RxBytes,TxBytes,LogRecordTime";

  private final static Logger log = LogManager.getLogger(LogRecord.class.getSimpleName());

  public LogRecord(ProgramProfiler progProfiler, NetworkProfiler netProfiler,
      Configuration config) {
    appName = progProfiler.getAppName();
    methodName = progProfiler.getMethodName();
    execLocation = progProfiler.getExecLocation().toString();
    prepareDataDuration = progProfiler.getPrepareDataDur();
    execDuration = progProfiler.getExecDur();
    pureDuration = progProfiler.getPureExecDur();

    networkType = NetworkProfiler.networkType;
    rtt = NetworkProfiler.rtt;
    ulRate = NetworkProfiler.lastUlRate;
    dlRate = NetworkProfiler.lastDlRate;

    logFilePath = config.getRapidLogFile();
    // db = DB.getInstance(config);
  }

  /**
   * Save these measurements on a log file and on the DB
   */
  public void save() {
    logRecordTime = System.currentTimeMillis();
    log.info(this.toString());
    saveToFile();
    saveToDbCache();
    // saveToDB();
  }

  private synchronized void saveToFile() {
    File logFile = new File(this.logFilePath);
    boolean logFileCreated;
    FileWriter logFileWriter = null;
    try {
      logFileCreated = logFile.createNewFile();
      logFileWriter = new FileWriter(logFile, true);
      if (logFileCreated) {
        logFileWriter.append(LogRecord.LOG_HEADERS + "\n");
      }
      logFileWriter.append(this.toString() + "\n");
      logFileWriter.flush();
    } catch (IOException e) {
      log.error("Not able to create the logFile " + logFilePath + ": " + e);
    } finally {
      if (logFileWriter != null) {
        try {
          logFileWriter.close();
        } catch (IOException e) {
          log.error("Error while closing logFileWriter: " + e);
        }
      }
    }
  }

  private void saveToDbCache() {
    DBCache dbCache = DBCache.getDbCache();
    // public DBEntry(String appName, String methodName, String execLocation, String networkType,
    // String networkSubType, int ulRate, int dlRate, long execDuration, long execEnergy)
    DBEntry dbEntry =
        new DBEntry(appName, methodName, execLocation, networkType, ulRate, dlRate, execDuration);
    dbCache.insertEntry(dbEntry);
  }

  /**
   * @deprecated
   */
  private void saveToDB() {
    List<String> keys = new ArrayList<>();
    List<String> values = new ArrayList<>();
    keys.add(DB.KEY_APP_NAME);
    values.add(appName);

    keys.add(DB.KEY_METHOD_NAME);
    values.add(methodName);

    keys.add(DB.KEY_EXEC_LOCATION);
    values.add(execLocation);

    keys.add(DB.KEY_EXEC_DURATION);
    values.add(Long.toString(execDuration));

    keys.add(DB.KEY_PURE_EXEC_DURATION);
    values.add(Long.toString(pureDuration));

    keys.add(DB.KEY_PREPARE_DATA_DURATION);
    values.add(Long.toString(prepareDataDuration));

    keys.add(DB.KEY_NETWORK_TYPE);
    values.add(networkType);

    keys.add(DB.KEY_RTT);
    values.add(Integer.toString(rtt));

    keys.add(DB.KEY_UL_RATE);
    values.add(Integer.toString(ulRate));

    keys.add(DB.KEY_DL_RATE);
    values.add(Integer.toString(dlRate));

    keys.add(DB.KEY_TIMESTAMP);
    values.add(Long.toString(logRecordTime));

    // try {
    // if (db.isConnected()) {
    // db.insertEntry(keys, values);
    // } else {
    // log.warn("Not connected to DB, not saving stats about this execution");
    // }
    // } catch (SQLException e) {
    // log.error("Exception while saving entry to DB: " + e);
    // }
  }

  public String toString() {
    StringBuilder s = new StringBuilder();

    s.append(appName + ",").append(methodName + ",").append(execLocation + ",")
        .append(prepareDataDuration + ",").append(execDuration + ",").append(pureDuration + ",")
        .append(threadCpuTime + ",").append(instructionCount + ",").append(methodCount + ",")
        .append(threadAllocSize + ",").append(threadGcInvocationCount + ",")
        .append(globalGcInvocationCount + ",").append(networkType + ",")
        .append(networkSubType + ",").append(rtt + ",").append(ulRate + ",").append(dlRate + ",")
        .append(rxBytes + ",").append(txBytes + ",").append(logRecordTime);

    return s.toString();
  }
}
