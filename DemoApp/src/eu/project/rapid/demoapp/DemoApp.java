package eu.project.rapid.demoapp;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import eu.project.rapid.ac.DFE;
import eu.project.rapid.common.RapidConstants.ExecLocation;

public class DemoApp {
  private DFE dfe;
  private int nrTests = 1;

  private final static Logger log = LogManager.getLogger(DemoApp.class.getSimpleName());

  public DemoApp() {
    dfe = DFE.getInstance();
    dfe.setUserChoice(ExecLocation.REMOTE);

    log.info("Testing JNI...");
    testHelloJni();

    log.info("Testing HelloWorld...");
    testHelloWorld();
    //
    // log.info("Testing Sum of two numbers...");
    // testSumNum();
    //
    // log.info("Testing NQueens...");
    // testNQueens();

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

  public static void main(String[] argv) {
    new DemoApp();
  }
}
