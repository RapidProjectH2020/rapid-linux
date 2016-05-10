package eu.project.rapid.demoapp;

import java.io.IOException;
import java.lang.reflect.Method;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import eu.project.rapid.ac.DFE;
import eu.project.rapid.ac.Remoteable;
import eu.project.rapid.demo.helloJNI.HelloJNI;

public class DemoApp extends Remoteable {

  private static final long serialVersionUID = 5541949167439814577L;
  private transient static DFE dfe;
  private static final int nrIterations = 1;

  private final static Logger log = LogManager.getLogger(DemoApp.class.getSimpleName());

  public DemoApp() {
    dfe = new DFE();

    for (int i = 0; i < nrIterations; i++) {
      helloWorld();
    }

    for (int i = 0; i < nrIterations; i++) {
      int sum = sumTwoNums(7, 12);
      System.out.println("The sum is " + sum);
    }

    GVirtusDemo gVirtusDemo = new GVirtusDemo(dfe);
    for (int i = 0; i < nrIterations; i++) {
      try {
        log.info("Running GVirtuS deviceQuery() demo");
        gVirtusDemo.deviceQuery();
        log.info("Successfully executed GVirtuS matrixMul() demo");
      } catch (IOException e) {
        log.error("Not possible to execute GVirtuS deviceQuery() demo: " + e);
      }
    }

    for (int i = 0; i < nrIterations; i++) {
      try {
        log.info("Running GVirtuS matrixMul() demo");
        gVirtusDemo.matrixMul();
        log.info("Successfully executed GVirtuS matrixMul() demo");
      } catch (IOException e) {
        log.error("Not possible to execute GVirtuS matrixMul() demo: " + e);
      }
    }
  }

  public int sumTwoNums(int a, int b) {
    Method toExecute;
    Class<?>[] parameterTypes = {int.class, int.class};
    Object[] parameterValues = {a, b};
    int result = -1;

    try {
      toExecute = this.getClass().getDeclaredMethod("localsumTwoNums", parameterTypes);
      result = (int) dfe.execute(toExecute, parameterValues, this);
    } catch (NoSuchMethodException | SecurityException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

    return result;
  }

  public int localsumTwoNums(int a, int b) {
    return a + b;
  }


  public void helloWorld() {
    Class<?>[] parameterTypes = {};
    try {
      dfe.execute(this.getClass().getDeclaredMethod("rapidhelloWorld", parameterTypes), this);
    } catch (NoSuchMethodException | SecurityException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  public void rapidhelloWorld() {
    System.out.println("Hello World!");
  }

  @Override
  public void copyState(Remoteable state) {
    System.out.println("Inside copyState");
  }

  public static void main(String[] argv) {
    new DemoApp();

    HelloJNI helloJni = new HelloJNI(dfe);
    for (int i = 0; i < nrIterations; i++) {
      int result = helloJni.printJava();
      System.out.println("The result of the native call: " + result);
    }
  }
}
