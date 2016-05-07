package eu.project.rapid.ac.profilers;

import eu.project.rapid.common.RapidConstants.ExecLocation;

public class ProgramProfiler {
  private String appName;
  private String methodName;
  private ExecLocation execLocation;
  private long startTime;
  private long execDur;
  private long pureExecDur;
  private long prepareDataDur;

  public ProgramProfiler(String appName, String methodName, ExecLocation execLocation) {
    this.appName = appName;
    this.methodName = methodName;
    this.execLocation = execLocation;
  }

  public void start() {
    startTime = System.nanoTime();
  }

  public void stop() {
    execDur = System.nanoTime() - startTime;
  }

  /**
   * @return the appName
   */
  public String getAppName() {
    return appName;
  }

  /**
   * @param appName the appName to set
   */
  public void setAppName(String appName) {
    this.appName = appName;
  }

  /**
   * @return the methodName
   */
  public String getMethodName() {
    return methodName;
  }

  /**
   * @param methodName the methodName to set
   */
  public void setMethodName(String methodName) {
    this.methodName = methodName;
  }

  /**
   * @return the execLocation
   */
  public ExecLocation getExecLocation() {
    return execLocation;
  }

  /**
   * @param execLocation the execLocation to set
   */
  public void setExecLocation(ExecLocation execLocation) {
    this.execLocation = execLocation;
  }

  /**
   * @return the execDur
   */
  public long getExecDur() {
    return execDur;
  }

  /**
   * @param execDur the execDur to set
   */
  public void setExecDur(long execDur) {
    this.execDur = execDur;
  }

  // PureExecTime refers to the time it takes to execute the method without considering networking
  // time.
  public void setPureExecDur(long pureExecDur) {
    this.pureExecDur = pureExecDur;
  }

  public long getPureExecDur() {
    return this.pureExecDur;
  }

  /**
   * @return the prepareDataDur
   */
  public long getPrepareDataDur() {
    return prepareDataDur;
  }

  /**
   * @param prepareDataDur the prepareDataDur to set
   */
  public void setPrepareDataDur(long prepareDataDur) {
    this.prepareDataDur = prepareDataDur;
  }
}
