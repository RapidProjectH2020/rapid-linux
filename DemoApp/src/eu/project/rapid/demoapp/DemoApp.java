package eu.project.rapid.demoapp;

import eu.project.rapid.ac.DFE;
import eu.project.rapid.common.RapidConstants.COMM_TYPE;
import eu.project.rapid.common.RapidConstants.ExecLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;

public class DemoApp {
  private DFE dfe;
  private int nrTests = 1;

  private final static Logger log = LogManager.getLogger(DemoApp.class.getSimpleName());

  public DemoApp() {

    // dfe = DFE.getInstance();
    // dfe = DFE.getInstance("10.0.0.13");
    dfe = DFE.getInstance("54.216.218.142");
    dfe.setUserChoice(ExecLocation.REMOTE);

    // log.info("Testing JNI...");
    // testHelloJni();

    log.info("Testing HelloWorld...");
    testHelloWorld();
    //
    // log.info("Testing Sum of two numbers...");
    // testSumNum();
    //
    // log.info("Testing NQueens...");
    // testNQueens();

    log.info("Testing overhead of connection, UL, and DL with SSL and CLEAR");
    onTestConnection();

    dfe.destroy();

    // testGvirtus();
  }

  private void testHelloWorld() {
    HelloWorld h = new HelloWorld(dfe);
    h.helloWorld();
  }

  private void testHelloJni() {
    HelloJNI helloJni = new HelloJNI(dfe);
    for (int i = 0; i < nrTests; i++) {
      int result = helloJni.printJava();
      log.info("The result of the native call: " + result);
    }
  }

  private void testSumNum() {
    TestSumNum t = new TestSumNum(dfe);
    int a = 3, b = 5;
    int result = t.sumTwoNums(a, b);
    log.info("Result of sum of two nums test: " + a + " + " + b + " = " + result);
  }

  private void testNQueens() {

    NQueens q = new NQueens(dfe);
    int result = -1;
    int[] nrQueens = {4, 5, 6, 7, 8};
    int[] nrTests = {1, 1, 1, 1, 1};

    for (int i = 0; i < nrQueens.length; i++) {
      for (int j = 0; j < nrTests[i]; j++) {
        result = q.solveNQueens(nrQueens[i]);
      }
      log.info("Result of NQueens(" + nrQueens[i] + "): " + result);
    }
  }

  private void testGvirtus() {
    new GVirtusDemo(dfe);
  }


  public void onTestConnection() {
    log.info("Testing how much it takes to connect to the clone using different strategies.");

    int NR_TESTS = 200;
    String[] stringCommTypes = {"CLEAR", "SSL"};
    COMM_TYPE[] commTypes = {COMM_TYPE.CLEAR, COMM_TYPE.SSL};

    for (int i = 0; i < commTypes.length; i++) {
      File logFile = new File(
          dfe.getRapidFolder() + File.separator + "connection_test_" + stringCommTypes[i] + ".csv");
      try {
        log.info("Creating the connection log file: " + logFile.getAbsolutePath());
        logFile.delete();
        logFile.createNewFile();
        BufferedWriter buffLogFile = new BufferedWriter(new FileWriter(logFile, true));

        for (int i1 = 0; i1 < NR_TESTS; i1++) {
          sleep(1 * 1000);
          dfe.testConnection(commTypes[i], buffLogFile);
        }

        buffLogFile.close();
      } catch (IOException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
    }

    sleep(10 * 1000);
    onTestSendBytes();
  }

  public void onTestSendBytes() {
    log.info(
        "Testing how much it takes to send data of different size to the clone using different strategies.");

    // SSL_NO_REUSE makes sense only for the connection test.
    // Once the connection is setup it works exactly like SSL.
    // Since I am going to show only the results for 4 bytes, 1KB, and 1MB measure the energy only
    // for these sizes
    COMM_TYPE[] commTypes = {COMM_TYPE.CLEAR, COMM_TYPE.SSL};
    String[] stringCommTypes = {"CLEAR", "SSL"};
    int[] nrTests = {200, 100, 20};
    int[] bytesToSendSize = {4, 100 * 1024, 1024 * 1024};
    String[] bytesSizeToSendString = {"4B", "100KB", "1MB"};

    // int[] nrTests = {50};
    // int[] bytesToSendSize = {1024 * 1024};
    // String[] bytesSizeToSendString = {"1MB"};

    ArrayList<byte[]> bytesToSend = new ArrayList<byte[]>();
    for (int i = 0; i < bytesToSendSize.length; i++) {
      byte[] temp = new byte[bytesToSendSize[i]];
      new Random().nextBytes(temp);
      bytesToSend.add(temp);
    }

    // Measure the increase of data size due to encryption strategies.
    File bytesLogFile =
        new File(dfe.getRapidFolder() + File.separator + "send_bytes_test_size.csv");
    BufferedWriter buffBytesLogFile = null;

    try {
      bytesLogFile.delete();
      bytesLogFile.createNewFile();
      buffBytesLogFile = new BufferedWriter(new FileWriter(bytesLogFile, true));
    } catch (IOException e2) {
      e2.printStackTrace();
    }

    // Sleep 10 seconds before starting the energy measurement experiment to avoid the errors
    // introduced by pressing the button
    sleep(10 * 1000);

    for (int j = 0; j < bytesToSendSize.length; j++) {
      try {
        buffBytesLogFile.write(bytesSizeToSendString[j] + "\t");
      } catch (IOException e2) {
        e2.printStackTrace();
      }

      for (int i1 = 0; i1 < commTypes.length; i1++) {

        File logFile = new File(dfe.getRapidFolder() + File.separator + "send_" + bytesToSendSize[j]
            + "_bytes_test_" + stringCommTypes[i1] + ".csv");

        try {
          logFile.delete();
          logFile.createNewFile();
          BufferedWriter buffLogFile = new BufferedWriter(new FileWriter(logFile, true));

          double totalTxBytes = 0;
          for (int i11 = 0; i11 < nrTests[j]; i11++) {
            try {
              // Change the connection to the new communication protocol
              // I need to reset the connection otherwise the objects are not sent, only the pointer
              // is sent.
              dfe.testConnection(commTypes[i1], null);
            } catch (IOException e1) {
              e1.printStackTrace();
            }
            totalTxBytes += dfe.testSendBytes(bytesToSendSize[j], bytesToSend.get(j), buffLogFile);
          }

          totalTxBytes /= nrTests[j];

          if (i1 == commTypes.length - 1)
            buffBytesLogFile.write(totalTxBytes + "\n");
          else
            buffBytesLogFile.write(totalTxBytes + "\t");

          buffLogFile.close();
        } catch (IOException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
        sleep(1 * 1000);
      }
      sleep(1 * 1000);
    }

    try {
      buffBytesLogFile.close();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

    log.info("Finished sendBytes test");
    sleep(10 * 1000);
    onTestReceiveBytes();
  }

  public void onTestReceiveBytes() {
    log.info(
        "Testing how much it takes to receive data of different size from the clone using different strategies.");

    // SSL_NO_REUSE makes sense only for the connection test.
    // Once the connection is setup it works exactly like SSL.
    // Since I am going to show only the results for 4 bytes, 1KB, and 1MB measure the energy only
    // for these sizes
    COMM_TYPE[] commTypes = {COMM_TYPE.CLEAR, COMM_TYPE.SSL};
    String[] stringCommTypes = {"CLEAR", "SSL"};
    int[] nrTests = {200, 100, 20};
    int[] bytesToReceiveSize = {4, 100 * 1024, 1024 * 1024};
    String[] bytesSizeToReceiveString = {"4B", "100KB", "1MB"};

    // int[] nrTests = {5};
    // int[] bytesToReceiveSize = {1 * 1024 * 1024};
    // String[] bytesSizeToReceiveString = {"1MB"};

    // Measure the increase of data size due to encryption strategies.
    File bytesLogFile =
        new File(dfe.getRapidFolder() + File.separator + "receive_bytes_test_size.csv");
    BufferedWriter buffBytesLogFile = null;

    try {
      bytesLogFile.delete();
      bytesLogFile.createNewFile();
      buffBytesLogFile = new BufferedWriter(new FileWriter(bytesLogFile, true));
    } catch (IOException e2) {
      e2.printStackTrace();
    }

    // Sleep 10 seconds before starting the energy measurement experiment to avoid the errors
    // introduced by pressing the button
    sleep(10 * 1000);

    for (int bs = 0; bs < bytesToReceiveSize.length; bs++) {
      try {
        buffBytesLogFile.write(bytesSizeToReceiveString[bs] + "\t");
      } catch (IOException e2) {
        e2.printStackTrace();
      }

      for (int ct = 0; ct < commTypes.length; ct++) {

        File logFile = new File(dfe.getRapidFolder() + File.separator + "receive_"
            + bytesToReceiveSize[bs] + "_bytes_test_" + stringCommTypes[ct] + ".csv");

        try {
          logFile.delete();
          logFile.createNewFile();
          BufferedWriter buffLogFile = new BufferedWriter(new FileWriter(logFile, true));

          double totalRxBytes = 0;
          for (int i = 0; i < nrTests[bs]; i++) {

            log.info("Receiving " + bytesToReceiveSize[bs] + " bytes using " + stringCommTypes[ct]
                + " connection");

            try {
              // Change the connection to the new communication protocol
              dfe.testConnection(commTypes[ct], null);
            } catch (IOException e1) {
              e1.printStackTrace();
            }
            totalRxBytes += dfe.testReceiveBytes(bytesToReceiveSize[bs], buffLogFile);
          }

          totalRxBytes /= nrTests[bs];

          if (ct == commTypes.length - 1)
            buffBytesLogFile.write(totalRxBytes + "\n");
          else
            buffBytesLogFile.write(totalRxBytes + "\t");

          buffLogFile.close();
        } catch (IOException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        } catch (ClassNotFoundException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
        sleep(1 * 1000);
      }
      sleep(1 * 1000);
    }

    try {
      buffBytesLogFile.close();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  private void sleep(int millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  public static void main(String[] argv) {
    new DemoApp();
  }
}
