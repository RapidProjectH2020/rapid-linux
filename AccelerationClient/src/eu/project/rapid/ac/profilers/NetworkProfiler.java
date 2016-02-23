package eu.project.rapid.ac.profilers;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import eu.project.rapid.common.Commands;
import eu.project.rapid.common.Constants;

public class NetworkProfiler {

  private static InputStream vmIs;
  private static OutputStream vmOs;

  private static final Logger log = LogManager.getLogger(NetworkProfiler.class.getSimpleName());
  public static final int rttInfinite = Constants.GIGA;
  private static final int rttTimes = 5;
  public static int rtt = rttInfinite;

  public NetworkProfiler(InputStream is, OutputStream os) {
    vmIs = is;
    vmOs = os;
  }

  public static int rttPing() {
    log.info("Sending ping messages to measure the RTT with the server");

    int tempRtt = 0;
    for (int i = 0; i < rttTimes; i++) {
      try {
        long start = System.nanoTime();
        vmOs.write(Commands.PING);
        int response = vmIs.read();
        if (response == Commands.PING) {
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

    rtt = tempRtt / rttTimes;

    log.info("Measured RTT: " + rtt + " ns");
    return rtt;
  }
}
