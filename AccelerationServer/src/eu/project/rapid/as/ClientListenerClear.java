package eu.project.rapid.as;

import eu.project.rapid.utils.Configuration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The AS waiting for clear client connections.<br>
 * The clients can be devices (AC) or other VMs (AS).
 *
 * @author sokol
 */
public class ClientListenerClear implements Runnable {

    private static final Logger log = LogManager.getLogger(ClientListenerClear.class.getSimpleName());
    private Configuration config;
    private ExecutorService threadPool = Executors.newFixedThreadPool(10);

    public ClientListenerClear(Configuration config) {
        this.config = config;
    }

    @Override
    public void run() {

        try (ServerSocket serverSocket = new ServerSocket(config.getAsPort())) {

            log.info("AS Clear server started on port " + config.getAsPort());
            while (true) {
                Socket clientSocket = serverSocket.accept();
                log.info("New client connected in clear");
                threadPool.execute(new AppHandler(clientSocket, config));
            }
        } catch (IOException e) {
            log.error("Not possible to start the AS Clear server: " + e);
        }
    }
}
