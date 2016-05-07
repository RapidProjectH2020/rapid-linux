package eu.project.rapid.as;

import java.io.IOException;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import eu.project.rapid.utils.Configuration;

public class AsSslThread implements Runnable {

  private static final Logger log = LogManager.getLogger(AsSslThread.class.getSimpleName());
  private Configuration config;

  public AsSslThread(Configuration config) {
    this.config = config;
  }

  @Override
  public void run() {

    // Specifying the Keystore details
    // System.setProperty("javax.net.ssl.keyStore", config.getSslKeyStoreName());
    // System.setProperty("javax.net.ssl.keyStorePassword", config.getSslCertPassword());

    try {
      // Initialize the Server Socket
      SSLContext sslContext = SSLContext.getInstance("SSL");
      sslContext.init(config.getKmf().getKeyManagers(), null, null);

      SSLServerSocketFactory factory = (SSLServerSocketFactory) sslContext.getServerSocketFactory();
      log.info("factory created");

      SSLServerSocket serverSocket =
          (SSLServerSocket) factory.createServerSocket(config.getAsPortSsl());
      log.info("server socket created");

      // SSLContext sslContext = config.getSslContext();
      // SSLServerSocketFactory factory = (SSLServerSocketFactory)
      // sslContext.getServerSocketFactory();
      //
      // SSLServerSocket sslServerSocket =
      // (SSLServerSocket) factory.createServerSocket(config.getAsPortSsl());

      log.info("AS SSL thread started on port " + config.getAsPortSsl());
      while (true) {
        Socket clientSocket = serverSocket.accept();
        log.info("New client connected using SSL");
        new AppHandler(clientSocket, config);
      }

    } catch (IOException | NoSuchAlgorithmException | KeyManagementException e) {
      log.error("Could not create SSL context: " + e);
      e.printStackTrace();
    }
  }
}
