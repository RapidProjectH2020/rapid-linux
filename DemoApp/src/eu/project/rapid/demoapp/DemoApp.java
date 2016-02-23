package eu.project.rapid.demoapp;

import java.lang.reflect.Method;

import eu.project.rapid.ac.DFE;
import eu.project.rapid.ac.Remoteable;
import eu.project.rapid.demo.helloJNI.HelloJNI;

public class DemoApp extends Remoteable {

  private static final long serialVersionUID = 5541949167439814577L;
  private transient static DFE dfe;

  public DemoApp() {
    dfe = new DFE();

    for (int i = 0; i < 100; i++) {
      helloWorld();
    }

    int sum = sumTwoNums(7, 12);
    System.out.println("The sum is " + sum);

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
    int result = helloJni.printJava();
    System.out.println("The result of the native call: " + result);

  }
}
