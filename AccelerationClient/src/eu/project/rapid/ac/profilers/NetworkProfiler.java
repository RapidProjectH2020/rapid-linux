package eu.project.rapid.ac.profilers;

import eu.project.rapid.common.RapidMessages;
import eu.project.rapid.common.RapidUtils;
import eu.project.rapid.utils.Configuration;
import eu.project.rapid.utils.Constants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class NetworkProfiler {

  private static final Logger log = LogManager.getLogger(NetworkProfiler.class.getSimpleName());
  public static final int rttInfinite = Constants.GIGA;
  private static final int rttTimes = 5;

  public static String networkType = "WiFi";
  private static final int BUFFER_SIZE = 10 * 1024;
  private static byte[] buffer = new byte[BUFFER_SIZE];
  private static final int bwWindowMaxLength = 20;
  private static List<Integer> ulRateHistory = new LinkedList<>();
  private static List<Integer> dlRateHistory = new LinkedList<>();
  public static int rtt = rttInfinite;
  public static int lastUlRate = -1;
  public static int lastDlRate = -1;

  private static ScheduledFuture<?> rttHandler;
  private static ScheduledFuture<?> uploadHandler;
  private static ScheduledFuture<?> downloadHandler;
  private static boolean monitoring = false;
  private static String vmIp;
  private static int vmPortBandwidthTest;

  /**
   * Currently do nothing. Here we can call the energy measurement methods, if needed, but for the
   * Linux devices we will not need them.
   */
  void start() {}

  void stop() {
    if (rttHandler != null) {
      rttHandler.cancel(true);
    }

    if (uploadHandler != null) {
      uploadHandler.cancel(true);
    }

    if (downloadHandler != null) {
      downloadHandler.cancel(true);
    }
  }

  private static void addNewUlRateEstimate(long bytes, long nanoTime) {

    log.info("Sent " + bytes + " bytes in " + nanoTime + "ns");
    int ulRate = (int) ((((double) 8 * bytes) / nanoTime) * 1000000000);
    log.info("Estimated upload bandwidth: " + ulRate + " b/s (" + ulRate / 1000 + " Kbps)");

    // Rule 1: if the number of bytes sent was bigger than 10KB and the ulRate is small then keep
    // it, otherwise throw it
    // Rule 2: if the number of bytes sent was bigger than 50KB then keep the calculated ulRate
    if (bytes < 10 * 1000) {
      return;
    } else if (bytes < 50 * 1000 && ulRate > 250 * 1000) {
      return;
    }

    if (ulRateHistory.size() >= bwWindowMaxLength) {
      ulRateHistory.remove(0);
    }

    ulRateHistory.add(ulRate);
    NetworkProfiler.lastUlRate = ulRate;
  }

  private static void addNewDlRateEstimate(long bytes, long nanoTime) {

    log.info("Received " + bytes + " bytes in " + nanoTime + "ns");
    int dlRate = (int) ((((double) 8 * bytes) / nanoTime) * 1000000000);
    log.info("Estimated download bandwidth: " + dlRate + " b/s (" + dlRate / 1000 + " Kbps)");

    // Rule 1: if the number of bytes sent was bigger than 10KB and the ulRate is small then keep
    // it, otherwise throw it
    // Rule 2: if the number of bytes sent was bigger than 50KB then keep the calculated ulRate
    if (bytes < 10 * 1000)
      return;
    else if (bytes < 50 * 1000 && dlRate > 250 * 1000)
      return;

    if (dlRateHistory.size() >= bwWindowMaxLength) {
      dlRateHistory.remove(0);
    }

    dlRateHistory.add(dlRate);
    NetworkProfiler.lastDlRate = dlRate;
  }

  /**
   * Start threads that measure the RTT, ulRate, and dlRate with a given frequency.
   */
  public static synchronized void startNetworkMonitoring(Configuration config) {
    if (!monitoring) {
      monitoring = true;

      vmIp = config.getVm().getIp();
      vmPortBandwidthTest = config.getVm().getClonePortBandwidthTest();
      ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
      rttHandler = scheduler.scheduleAtFixedRate(new RttMeasurer(), 0, 13 * 60, TimeUnit.SECONDS);
      downloadHandler =
          scheduler.scheduleAtFixedRate(new DownloadRateMeasurer(), 1, 29 * 60, TimeUnit.SECONDS);
      uploadHandler =
          scheduler.scheduleAtFixedRate(new UploadRateMeasurer(), 5, 31 * 60, TimeUnit.SECONDS);
    }
  }

  /**
   * Class to be used for measuring the data rate and RTT every 30 minutes.
   */
  private static class RttMeasurer implements Runnable {
    @Override
    public void run() {
      log.info("Measuring the RTT");
      NetworkProfiler.measureRtt();
    }
  }

  /**
   * Class to be used for measuring the data rate and RTT every 30 minutes.
   */
  private static class UploadRateMeasurer implements Runnable {
    @Override
    public void run() {
      log.info("Measuring the upload rate");
      NetworkProfiler.measureUlRate();
    }
  }

  /**
   * Class to be used for measuring the data rate and RTT every 30 minutes.
   */
  private static class DownloadRateMeasurer implements Runnable {
    @Override
    public void run() {
      log.info("Measuring the download rate");
      NetworkProfiler.measureDlRate();
    }
  }

  static void measureRtt() {
    log.info("Sending ping messages to measure the RTT with the server");

    int tempRtt = 0;
    try (final Socket clientSocket = new Socket(vmIp, vmPortBandwidthTest);
        OutputStream os = clientSocket.getOutputStream();
        InputStream is = clientSocket.getInputStream();
        DataInputStream dis = new DataInputStream(is)) {
      for (int i = 0; i < rttTimes; i++) {
        try {
          long start = System.nanoTime();
          os.write(RapidMessages.PING);
          int response = is.read();
          if (response == RapidMessages.PONG) {
            tempRtt += (System.nanoTime() - start) / 2;
          } else {
            log.warn("Bad response to PING");
            tempRtt = rttInfinite;
          }
        } catch (IOException e) {
          log.error("Error while sending PING message: " + e);
          tempRtt = rttInfinite;
        }
      }
    } catch (IOException e1) {
      log.error("Error while sending PING message: " + e1);
    }

    rtt = tempRtt / rttTimes;
    log.info("Measured RTT: " + rtt + " ns");
  }

  /**
   * Keep receiving data from the NetworkProfilerServer for 3 seconds.<br>
   * After the 3 second timeout, close the sockets to cause an Exception and force the InputStream
   * to stopAndSave listening. Use the measured values to estimate the download bandwidth.
   */
  static void measureDlRate() {

    long time = 0;
    long rxBytes = 0;
    try (final Socket clientSocket = new Socket(vmIp, vmPortBandwidthTest);
        OutputStream os = clientSocket.getOutputStream();
        InputStream is = clientSocket.getInputStream();
        DataInputStream dis = new DataInputStream(is)) {

      os.write(RapidMessages.DOWNLOAD_FILE);

      new Thread(new Runnable() {

        @Override
        public void run() {
          long t0 = System.nanoTime();
          long elapsed = 0;
          while (elapsed < 3000) {
            try {
              Thread.sleep(3000 - elapsed);
            } catch (InterruptedException e1) {
            } finally {
              elapsed = (System.nanoTime() - t0) / 1000000;
            }
          }
          // After 3 seconds close the streams to force an exception on the listening InputStream
          // below.
          RapidUtils.closeQuietly(os);
          RapidUtils.closeQuietly(is);
          RapidUtils.closeQuietly(clientSocket);
        }
      }).start();

      time = System.nanoTime();
      while (true) {
        rxBytes += is.read(buffer);
        os.write(1);
      }
    } catch (IOException e) {
      log.info("Finished measuring the dlRate");
    } finally {
      time = System.nanoTime() - time;
      addNewDlRateEstimate(rxBytes, time);
    }
  }

  static void measureUlRate() {

    long txTime;
    long txBytes;

    try (final Socket clientSocket = new Socket(vmIp, vmPortBandwidthTest);
        OutputStream os = clientSocket.getOutputStream();
        InputStream is = clientSocket.getInputStream();
        DataInputStream dis = new DataInputStream(is)) {

      os.write(RapidMessages.UPLOAD_FILE);

      while (true) {
        os.write(buffer);
        is.read();
      }

    } catch (IOException e) {
      log.info("Finished measuring the ulRate");
    } finally {

      try (Socket clientSocket = new Socket(vmIp, vmPortBandwidthTest);
          OutputStream os = clientSocket.getOutputStream();
          InputStream is = clientSocket.getInputStream();
          DataInputStream dis = new DataInputStream(is)) {

        os.write(RapidMessages.UPLOAD_FILE_RESULT);
        txBytes = dis.readLong();
        txTime = dis.readLong();

        addNewUlRateEstimate(txBytes, txTime);

      } catch (Exception e) {
        log.error("Error while receiving ulRate from VM: " + e);
      }
    }
  }
}
