package eu.project.rapid.ac;

import eu.project.rapid.common.Constants.ExecLocation;

/**
 * The Design Space Explorer class is responsible for deciding where to execute a method.
 * 
 * @author sokol
 *
 */
public class DSE {

  public DSE() {

  }

  public ExecLocation findExecLocation(String appName, String methodName) {
    ExecLocation location = ExecLocation.REMOTE;

    return location;
  }
}
