package eu.project.rapid.demo.helloJNI;

import java.lang.reflect.Method;

import eu.project.rapid.ac.DFE;
import eu.project.rapid.ac.Remoteable;
import eu.project.rapid.utils.Utils;

public class HelloJNI extends Remoteable {

  private static final long serialVersionUID = -5942880824910953975L;
  private transient DFE dfe;

  static {
    if (Utils.isMac()) {
      System.load("/Users/sokol/rapid-client/demo/HelloJNI.jnilib");
    } else if (Utils.isLinux()) {
      System.load("/home/gjigandi/rapid-client/libhellojni.so");
      // System.err.println("Sokol: library not compiled for Linux.");
    } else if (Utils.isWindows()) {
      System.err.println("Sokol: library not compiled for Windows.");
    }
  }

  public HelloJNI(DFE dfe) {
    this.dfe = dfe;
  }

  /**
   * A native method implemented in C++
   */
  public native int print();

  public int printJava() {
    int result = 0;
    Class<?>[] parameterTypes = {};
    Method method;
    try {
      method = this.getClass().getMethod("rapidprintJava", parameterTypes);
      result = (int) dfe.execute(method, this);
    } catch (NoSuchMethodException | SecurityException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

    return result;
  }

  public int rapidprintJava() {
    return print();
  }

  @Override
  public void copyState(Remoteable state) {}
}
