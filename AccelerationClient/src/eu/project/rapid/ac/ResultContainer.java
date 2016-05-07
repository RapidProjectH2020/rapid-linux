package eu.project.rapid.ac;

import java.io.Serializable;

public class ResultContainer implements Serializable {

  private static final long serialVersionUID = 6289277906217259082L;

  public Object objState;
  public Object functionResult;
  public long getObjectDuration;
  public long pureExecutionDuration;

  /**
   * Wrapper of results returned by remote server - state of the object the call was executed on and
   * function result itself
   * 
   * @param state state of the remoted object
   * @param result result of the function executed on the object
   */
  public ResultContainer(Object state, Object result, long getObjectDuration, long duration) {
    objState = state;
    functionResult = result;
    this.getObjectDuration = getObjectDuration;
    pureExecutionDuration = duration;
  }

  /**
   * Used when an exception happens, to return the exception as a result of remote invocation
   * 
   * @param result
   */
  public ResultContainer(Object result, long getObjectDuration) {
    objState = null;
    functionResult = result;
    this.getObjectDuration = getObjectDuration;
    pureExecutionDuration = -1;
  }
}
