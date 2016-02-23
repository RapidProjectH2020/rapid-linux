package eu.project.rapid.common;

import java.io.Serializable;

public class VM implements Serializable {
  private static final long serialVersionUID = -4015502984854956971L;

  private int id;
  private String ip;
  private int port;
  private int sslPort;

  public VM() {
    this(-1, null, -1, -1);
  }

  public VM(int id) {
    this(id, null, -1, -1);
  }

  public VM(int id, String ip, int port, int sslPort) {
    this.id = id;
    this.ip = ip;
    this.port = port;
    this.sslPort = sslPort;
  }

  @Override
  public String toString() {
    return "[vmID=" + this.getId() + ", ip=" + this.getIp() + ", port=" + this.getPort()
        + ", sslPort=" + this.getSslPort() + "]";
  }

  /**
   * @return the id
   */
  public int getId() {
    return id;
  }

  /**
   * @param id the id to set
   */
  public void setId(int id) {
    this.id = id;
  }

  /**
   * @return the ip
   */
  public String getIp() {
    return ip;
  }

  /**
   * @param ip the ip to set
   */
  public void setIp(String ip) {
    this.ip = ip;
  }

  /**
   * @return the port
   */
  public int getPort() {
    return port;
  }

  /**
   * @param port the port to set
   */
  public void setPort(int port) {
    this.port = port;
  }

  /**
   * @return the sslPort
   */
  public int getSslPort() {
    return sslPort;
  }

  /**
   * @param sslPort the sslPort to set
   */
  public void setSslPort(int sslPort) {
    this.sslPort = sslPort;
  }
}
