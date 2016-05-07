package eu.project.rapid.ac;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import eu.project.rapid.ac.profilers.NetworkProfiler;
import eu.project.rapid.common.RapidConstants.ExecLocation;
import eu.project.rapid.utils.Configuration;
import eu.project.rapid.utils.DB;

/**
 * The Design Space Explorer class is responsible for deciding where to execute a method.
 * 
 * @author sokol
 *
 */
public class DSE {

  private Configuration config;

  private static boolean VERBOSE_LOG = false;

  // To be used in case of no previous remote execution.
  // If the ulRate and dlRate are bigger than these values then we offload
  private static final int MIN_UL_RATE_OFFLOAD_1_TIME = 256 * 1000; // b/s
  private static final int MIN_DL_RATE_OFFLOAD_1_TIME = 256 * 1000; // b/s

  private int programOrientedDecNr = 1; // Keep track when the decision is taken

  private static final Logger log = LogManager.getLogger(DSE.class.getName());

  public DSE(Configuration config) {
    this.config = config;
  }

  public ExecLocation findExecLocation(String appName, String methodName) {
    if (shouldOffload(appName, methodName, NetworkProfiler.lastUlRate,
        NetworkProfiler.lastDlRate)) {
      log.info("Decided to execute REMOTE");
      return ExecLocation.REMOTE;
    }
    log.info("Decided to execute LOCAL");
    return ExecLocation.LOCAL;
  }

  /**
   * @param appName
   * @param methodName
   * @param currUlRate
   * @param currDlRate
   * @return <b>True</b> if the method should be executed remotely<br>
   *         <b>False</b> otherwise.
   */
  private boolean shouldOffload(String appName, String methodName, int currUlRate, int currDlRate) {
    DB db = DB.getInstance(config);

    if (!db.isConnected()) {
      log.warn("The DB containing the methods' history is null. Returning a random result.");
      if (new Random().nextInt() % 2 == 0) {
        log.info("Decided randomly to offload");
        return true;
      }
      log.info("Decided randomly to not offload");
      return false;
    }

    log.info("Trying to decide where to execute the method: appName=" + appName + ", methodName="
        + methodName + ", currUlRate=" + currUlRate + ", currDlRate=" + currDlRate);

    // Variables needed for the local executions
    int nrLocalExec = 0;
    long meanDurLocal = 0;

    // Variables needed for the remote executions
    int nrRemoteExec = 0;
    long meanDurRemote1 = 0;
    long meanDurRemote2 = 0;
    long meanDurRemote = 0;

    // Check if the method has been executed LOCALLY in previous runs
    String localSelection = "SELECT " + DB.KEY_EXEC_DURATION + ", " + DB.KEY_TIMESTAMP + " FROM "
        + DB.LOG_TABLE + " WHERE " + DB.KEY_APP_NAME + " = '" + appName + "' AND "
        + DB.KEY_METHOD_NAME + " = '" + methodName + "' AND " + DB.KEY_EXEC_LOCATION + " = '"
        + ExecLocation.LOCAL + "'";

    ResultSet localResults;
    try {
      localResults = db.getAllEntries(localSelection);
      if (localResults.last()) {
        nrLocalExec = localResults.getRow();
        localResults.beforeFirst();
      }

      // Local part
      // Calculate the meanDurLocal and meanEnergyLocal from the previous runs.
      // Give more weight to recent measurements.
      // Assume the DB query returns the rows ordered by ID (check this).
      if (VERBOSE_LOG) {
        log.info("------------ The local executions of the method: " + nrLocalExec);
      }
      long localDuration;
      long[] localTimestamps = new long[nrLocalExec];
      int i = 0;
      while (localResults.next()) {
        localDuration = Long.parseLong(localResults.getString(DB.KEY_EXEC_DURATION));
        localTimestamps[i] = Long.parseLong(localResults.getString(DB.KEY_TIMESTAMP));

        meanDurLocal += localDuration;

        if (VERBOSE_LOG) {
          log.info("duration: " + localDuration + " timestamp: " + localTimestamps[i]);
        }

        i++;
        if (i > 1) {
          meanDurLocal /= 2;
        }
      }
      log.info("nrLocalExec: " + nrLocalExec);
      log.info("meanDurLocal: " + meanDurLocal + "ns (" + meanDurLocal / 1000000000.0 + "s)");

      /**
       * Check if the method has been executed REMOTELY in previous runs
       */
      String remoteSelection = "SELECT " + DB.KEY_EXEC_DURATION + ", " + DB.KEY_UL_RATE + ", "
          + DB.KEY_RTT + ", " + DB.KEY_DL_RATE + ", " + DB.KEY_TIMESTAMP + " FROM " + DB.LOG_TABLE
          + " WHERE " + DB.KEY_APP_NAME + " = '" + appName + "' AND " + DB.KEY_METHOD_NAME + " = '"
          + methodName + "' AND " + DB.KEY_EXEC_LOCATION + " = '" + ExecLocation.REMOTE + "' AND "
          + DB.KEY_NETWORK_TYPE + " = '" + NetworkProfiler.networkType + "'";

      ResultSet remoteResults = db.getAllEntries(remoteSelection);
      if (remoteResults.last()) {
        nrRemoteExec = remoteResults.getRow();
        remoteResults.beforeFirst();
      }

      // DECISION 1
      // If the number of previous remote executions is zero and the current connection is good
      // then offload the method to see how it goes.
      if (nrRemoteExec == 0) {

        programOrientedDecNr = 1;

        if (currUlRate > MIN_UL_RATE_OFFLOAD_1_TIME && currDlRate > MIN_DL_RATE_OFFLOAD_1_TIME) {
          log.info("Decision 1: No previous remote executions. Good connectivity.");
          return true;
        } else {
          log.info("Decision 1: No previous remote executions. Bad connectivity.");
          return false;
        }
      }

      // Remote part
      if (VERBOSE_LOG) {
        log.info("------------ The remote executions of the method: " + nrRemoteExec);
      }
      long[] remoteDurations = new long[nrRemoteExec];
      int[] remoteUlRates = new int[nrRemoteExec];
      int[] remoteDlRates = new int[nrRemoteExec];
      long[] remoteTimestamps = new long[nrRemoteExec];
      i = 0;
      while (remoteResults.next()) {
        remoteDurations[i] = Long.parseLong(remoteResults.getString(DB.KEY_EXEC_DURATION));
        remoteUlRates[i] = Integer.parseInt(remoteResults.getString(DB.KEY_UL_RATE));
        remoteDlRates[i] = Integer.parseInt(remoteResults.getString(DB.KEY_DL_RATE));
        remoteTimestamps[i] = Long.parseLong(remoteResults.getString(DB.KEY_TIMESTAMP));

        if (VERBOSE_LOG) {
          log.info("duration: " + remoteDurations[i] + " ulRate: " + remoteUlRates[i] + " dlRate: "
              + remoteDlRates[i] + " timestamp: " + remoteTimestamps[i]);
        }

        i++;
      }
      log.info("nrRemoteExec: " + nrRemoteExec);

      // DECISION 2
      int NR_TIMES_SWITCH_SIDES = 10;
      // Last local timestamp
      long lastLocalTimestamp =
          (nrLocalExec == 0) ? 0 : localTimestamps[localTimestamps.length - 1];
      // Skip all the remote timestamps that are smaller than the last local timestamp
      for (i = 0; i < nrRemoteExec && remoteTimestamps[i] < lastLocalTimestamp; i++);
      if ((nrRemoteExec - i) >= NR_TIMES_SWITCH_SIDES) {
        log.info("Decision 2: Too many remote executions in a row.");
        programOrientedDecNr = 2;
        return false;
      }

      // Do the same thing for the remote side
      // Last remote timestamp
      long lastRemoteTimestamp =
          (nrRemoteExec == 0) ? 0 : remoteTimestamps[remoteTimestamps.length - 1];
      // Skip all the local timestamps that are smaller than the last remote timestamp
      for (i = 0; i < nrLocalExec && localTimestamps[i] < lastRemoteTimestamp; i++);
      if ((nrLocalExec - i) >= NR_TIMES_SWITCH_SIDES) {
        log.info("Decision 2: Too many local executions in a row.");
        programOrientedDecNr = 2;
        return true;
      }

      // DECISION 3
      // Calculate two different mean values for the offloaded execution:
      // 1. The first are the same as for the local execution, gives more weight to recent runs
      // 2. The second are calculated as the average of the three closest values to the
      // currentUlRate
      // and currDlRate
      int minDistIndex1 = 0, minDistIndex2 = 0, minDistIndex3 = 0;
      double minDist1 = Double.POSITIVE_INFINITY, minDist2 = Double.POSITIVE_INFINITY,
          minDist3 = Double.POSITIVE_INFINITY;
      for (i = 0; i < nrRemoteExec; i++) {
        // Calculate the first meanDuration and meanEnergy
        // The first formula is the same as for the local executions,
        // gives more importance to the last measurements.
        meanDurRemote1 += remoteDurations[i];
        if (i > 0) {
          meanDurRemote1 /= 2;
        }

        // Keep the indexes of the three measurements that have
        // the smallest distance dist(ulRate, dlRate, currUlRate, currDlRate)
        // minDist1 < minDist2 < minDist3
        double newDist = dist(remoteUlRates[i], remoteDlRates[i], currUlRate, currDlRate);
        if (newDist < minDist1) {
          minDist3 = minDist2;
          minDistIndex3 = minDistIndex2;

          minDist2 = minDist1;
          minDistIndex2 = minDistIndex1;

          minDist1 = newDist;
          minDistIndex1 = i;
        } else if (newDist < minDist2) {
          minDist3 = minDist2;
          minDistIndex3 = minDistIndex2;

          minDist2 = newDist;
          minDistIndex2 = i;
        } else if (newDist < minDist3) {
          minDist3 = newDist;
          minDistIndex3 = i;
        }
      }

      // Give more weight to the closest point
      meanDurRemote2 = (((remoteDurations[minDistIndex3] + remoteDurations[minDistIndex2]) / 2)
          + remoteDurations[minDistIndex1]) / 2;

      meanDurRemote = (meanDurRemote1 + meanDurRemote2) / 2;

      log.info("meanDurRemote1: " + meanDurRemote1 + "  meanDurRemote2: " + meanDurRemote2);
      log.info("meanDurRemote: " + meanDurRemote + "ns (" + meanDurRemote / 1000000000.0 + "s)");

      log.info("Decision 3.");
      programOrientedDecNr = 3;
      log.info("Making a choice for fast execution");
      return meanDurRemote <= meanDurLocal;

    } catch (SQLException e) {
      log.error("SQLException while deciding offloading location: " + e);
      e.printStackTrace();
    }

    return false;
  }

  private double dist(int ul1, int dl1, int ul2, int dl2) {
    return Math.sqrt((ul2 - ul1) * (ul2 - ul1) + (dl2 - dl1) * (dl2 - dl1));
  }
}
