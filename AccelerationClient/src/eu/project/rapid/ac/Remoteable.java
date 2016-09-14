package eu.project.rapid.ac;

import java.io.File;
import java.io.Serializable;
import java.util.LinkedList;

public abstract class Remoteable implements Serializable {
  private static final long serialVersionUID = 1L;

  public abstract void copyState(Remoteable state);

  /**
   * Load all provided shared libraries - used when an exception is thrown on the server-side,
   * meaning that the necessary libraries have not been loaded. x86 version of the libraries
   * included in the APK of the remote application are then loaded and the operation is re-executed.
   * 
   * @param libFiles
   */
  public void loadLibraries(LinkedList<File> libFiles) {
    for (File libFile : libFiles) {
      System.out.println(
          "Loading library: " + libFile.getName() + " (" + libFile.getAbsolutePath() + ")");
      System.load(libFile.getAbsolutePath());
    }
  }

  /**
   * Override this method if you want to prepare the data before executing the method.
   * 
   * This can also be useful in case the class contains data that are not serializable but are
   * needed by the method when offloaded. Use this method to convert the data to some serializable
   * form before the method is offloaded.
   * 
   * Do not explicitly call this method. It will be automatically called by the framework before
   * offloading.
   */
  public void prepareDataOnClient() {};

  /**
   * Override this method if you want to prepare the data before executing the method on the VM.
   * 
   * This can also be useful in case the class contains data that are not serializable but are
   * needed by the method when offloaded. Use this method to de-serialize the data before the method
   * is executed on the VM.
   * 
   * Do not explicitly call this method. It will be automatically called by the framework before
   * method's execution.
   */
  public void prepareDataOnServer() {};

}
