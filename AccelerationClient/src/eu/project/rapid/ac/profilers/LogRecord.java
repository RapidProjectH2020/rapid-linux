package eu.project.rapid.ac.profilers;

import eu.project.rapid.ac.db.DBCache;
import eu.project.rapid.ac.db.DBEntry;
import eu.project.rapid.common.RapidConstants;
import eu.project.rapid.utils.Configuration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class LogRecord {
    private String logFilePath;
    // private DB db;

    private String appName;
    private String methodName;
    private RapidConstants.ExecLocation execLocation;
    private long prepareDataDuration;
    private long execDuration;
    private long pureDuration;

    private Long threadCpuTime;
    private int instructionCount;
    private int methodCount;
    private int threadAllocSize;
    private int threadGcInvocationCount;
    private int globalGcInvocationCount;

    private String networkType;
    private String networkSubType;
    private int rtt = -1;
    private int ulRate = -1;
    private int dlRate = -1;
    private long rxBytes = -1;
    private long txBytes = -1;

    private long logRecordTime;

    private static final String LOG_HEADERS =
            "#AppName,MethodName,ExecLocation,PrepareDataDuration,ExecDuration,PureDuration,"
                    + "ThreadCpuTime,InstructionCount,MethodCount,ThreadAllocSize,ThreadGcInvocCount,GlobalGcInvocCount,"
                    + "NetType,NetSubtype,RTT,UlRate,DlRate,RxBytes,TxBytes,LogRecordTime";

    private final static Logger log = LogManager.getLogger(LogRecord.class.getSimpleName());

    public LogRecord(ProgramProfiler progProfiler, NetworkProfiler netProfiler, Configuration config) {
        appName = progProfiler.getAppName();
        methodName = progProfiler.getMethodName();
        execLocation = progProfiler.getExecLocation();
        prepareDataDuration = progProfiler.getPrepareDataDur();
        execDuration = progProfiler.getExecDur();
        pureDuration = progProfiler.getPureExecDur();

        networkType = NetworkProfiler.networkType;
        rtt = NetworkProfiler.rtt;
        ulRate = NetworkProfiler.lastUlRate;
        dlRate = NetworkProfiler.lastDlRate;

        logFilePath = config.getRapidLogFile();
    }

    /**
     * Save these measurements on a log file and on the DB
     */
    void save() {
        logRecordTime = System.currentTimeMillis();
        log.info(this.toString());
        saveToFile();
        saveToDbCache();
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
        DBCache dbCache = DBCache.getDbCache(appName);
        // public DBEntry(String appName, String methodName, String execLocation, String networkType,
        // String networkSubType, int ulRate, int dlRate, long execDuration, long execEnergy)
        DBEntry dbEntry =
                new DBEntry(appName, methodName, execLocation, networkType, ulRate, dlRate, execDuration);
        dbCache.insertEntry(dbEntry);
    }

    @Override
    public String toString() {
        return appName + "," + methodName + "," + execLocation + "," +
                prepareDataDuration + "," + execDuration + "," + pureDuration +
                "," + threadCpuTime + "," + instructionCount + "," +
                methodCount + "," + threadAllocSize + "," +
                threadGcInvocationCount + "," + globalGcInvocationCount + "," +
                networkType + "," + networkSubType + "," + rtt +
                "," + ulRate + "," + dlRate + "," + rxBytes + "," +
                txBytes + "," + logRecordTime;
    }
}
