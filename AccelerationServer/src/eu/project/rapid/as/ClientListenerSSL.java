package eu.project.rapid.as;

import eu.project.rapid.utils.Configuration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.IOException;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ClientListenerSSL implements Runnable {

  private static final Logger log = LogManager.getLogger(ClientListenerSSL.class.getSimpleName());
  private Configuration config;
  private ExecutorService threadPool = Executors.newFixedThreadPool(1000);

  public ClientListenerSSL(Configuration config) {
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

      SSLServerSocketFactory factory = sslContext.getServerSocketFactory();
      log.info("factory created");

      SSLServerSocket serverSocket = (SSLServerSocket) factory.createServerSocket(config.getAsPortSsl());
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
        threadPool.execute(new AppHandler(clientSocket, config));
      }
    } catch (IOException | NoSuchAlgorithmException | KeyManagementException e) {
      log.error("Could not create SSL context: " + e);
      e.printStackTrace();
    }
  }
}
