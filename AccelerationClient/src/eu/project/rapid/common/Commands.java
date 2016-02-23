package eu.project.rapid.common;

public final class Commands {

  // Common commands
  public static final byte ERROR = 0;
  public static final byte OK = 1;
  public static final byte PING = 2;

  // Commands AS <-> DS/SLAM
  public static final byte AS_RM_REGISTER_SLAM = 3;
  public static final byte AS_RM_REGISTER_DS = 4;

  // Commands AC <-> DS/SLAM
  public static final byte AC_REGISTER_PREV_DS = 5;
  public static final byte AC_REGISTER_NEW_DS = 6;
  public static final byte AC_REGISTER_SLAM = 7;

  // Commands AC <-> AS
  public static final byte AC_REGISTER_AS = 8;
  public static final byte AS_APP_REQ_AC = 9;
  public static final byte AS_APP_PRESENT_AC = 10;
  public static final byte AC_OFFLOAD_REQ_AS = 11;

  // Commands AC <-> AC_RM
  public static final byte AC_HELLO_AC_RM = 1;

  private Commands() {}
}
