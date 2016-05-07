package eu.project.rapid.as;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import eu.project.rapid.common.RapidMessages;
import eu.project.rapid.common.RapidUtils;
import eu.project.rapid.utils.Configuration;

/**
 * Listen for phone connections for measuring the data rate. The phone will send/receive some data
 * for 3 seconds.
 *
 */
public class NetworkProfilerServer implements Runnable {

  private static final Logger log = LogManager.getLogger(NetworkProfilerServer.class.getName());

  private Configuration config;
  private ServerSocket serverSocket;

  private long totalBytesRead;
  private long totalTimeBytesRead;
  private static final int BUFFER_SIZE = 10 * 1024;
  private byte[] buffer;

  public NetworkProfilerServer(Configuration config) {
    this.config = config;
    buffer = new byte[BUFFER_SIZE];
    new Random().nextBytes(buffer);
  }

  @Override
  public void run() {
    try {
      serverSocket = new ServerSocket(config.getClonePortBandwidthTest());
      while (true) {
        Socket clientSocket = serverSocket.accept();
        new Thread(new ClientThread(clientSocket)).start();
      }
    } catch (IOException e) {
      log.error(
          "Could not start server on port " + config.getClonePortBandwidthTest() + " (" + e + ")");
    }
  }

  private class ClientThread implements Runnable {

    private Socket clientSocket;

    public ClientThread(Socket clientSocket) {
      this.clientSocket = clientSocket;
    }

    @Override
    public void run() {
      int request = 0;

      try (InputStream is = clientSocket.getInputStream();
          OutputStream os = clientSocket.getOutputStream();
          DataOutputStream dos = new DataOutputStream(os);) {

        while (request != -1) {
          request = is.read();

          switch (request) {

            case RapidMessages.PING:
              os.write(RapidMessages.PONG);
              break;

            case RapidMessages.UPLOAD_FILE:
              new Thread(new Runnable() {

                @Override
                public void run() {
                  boolean threeSec = false;
                  while (!threeSec) {
                    try {
                      Thread.sleep(3000);
                      threeSec = true;
                    } catch (InterruptedException e1) {
                    }
                  }
                  // Close the streams to force an exception on the InputStream listening below.
                  RapidUtils.closeQuietly(os);
                  RapidUtils.closeQuietly(is);
                  RapidUtils.closeQuietly(clientSocket);
                }
              }).start();

              totalTimeBytesRead = System.nanoTime();
              totalBytesRead = 0;
              while (true) {
                totalBytesRead += is.read(buffer);
                os.write(1);
              }

            case RapidMessages.UPLOAD_FILE_RESULT:
              dos.writeLong(totalBytesRead);
              dos.writeLong(totalTimeBytesRead);
              dos.flush();
              break;

            case RapidMessages.DOWNLOAD_FILE:
              // Used for measuring the dlRate on the phone
              while (true) {
                os.write(buffer);
                is.read();
              }
          }
        }

      } catch (IOException e) {
        log.error("Error while receiving/sending data for bandwidth measurement" + e);
      } finally {
        log.info("Client finished bandwidth measurement: " + request);

        if (request == RapidMessages.UPLOAD_FILE)
          totalTimeBytesRead = System.nanoTime() - totalTimeBytesRead;
      }
    }
  }
}
