package eu.project.rapid.utils;

import java.io.File;

/**
 * Default values to use if no configuration file is provided.<br>
 * This file will be utilized by Java's Properties class, which only deals with String objects. For
 * this reason we represent all values here as Strings.
 * 
 * @author sokol
 *
 */
public final class Constants {

  /************ COMMON SIDE ************/
  // The config file should be put on the classpath of the project
  // The number of recent method executions to keep in DB so that they can be used for offloading
  // decision.
  public static final int MAX_METHOD_EXEC_HISTORY = 50;
  public static final String CONFIG_PROPERTIES = "config.properties";
  public static final String LOG_FILE_NAME_KEY = "rapid-log";
  public static final String LOG_FILE_NAME_DEFAULT = "rapid-log.csv";
  public static final String DB_FILE_NAME_KEY = "rapidDb";
  public static final String DB_FILE_NAME_DEFAULT = "rapidDb";
  // DB drivers
  public static final String DB_JDBC_DRIVER_KEY = "dbDriver";
  public static final String DB_JDBC_DRIVER_DEFAULT = "com.mysql.jdbc.Driver";
  public static final String DB_URL_KEY = "dbUrl";
  public static final String DB_URL_DEFAULT = "jdbc:mysql://localhost/"; // rapidDb?useUnicode=true&useJDBCCompliantTimezoneShift=true&useLegacyDatetimeCode=false&serverTimezone=Europe/Rome";
  // Database credentials
  public static final String DB_USER_KEY = "rapidDbUser";
  public static final String DB_PASS_KEY = "rapidDbPass";
  // GVirtuS ip and port
  public static final String GVIRTUS_IP_KEY = "gvirtusIp";
  public static final String GVIRTUS_PORT_KEY = "gvirtusPort";

  public static final String VMM_IP_KEY = "vmmIp";
  public static final String VMM_PORT_KEY = "vmmPort";

  public static final String TRUE = "true";
  public static final String FALSE = "false";
  public static final String DS_IP_KEY = "dsIp";
  public static final String DS_PORT_KEY = "dsPort";
  public static final String USER_ID_KEY = "userID";
  public static final String USER_ID_DEFAULT = "-1";
  public static final int KILO = 1000;
  public static final int MEGA = KILO * KILO;
  public static final int GIGA = KILO * KILO * KILO;
  public static final int KILO2 = 1024;
  public static final int MEGA2 = KILO2 * KILO2;
  public static final int GIGA2 = KILO2 * KILO2 * KILO2;

  // SSL related variables
  public static final String SSL_KEYSTORE_KEY = "sslKeyStore";
  public static final String SSL_KEYSTORE_DEFAULT = "client-keystore.jks";
  public static final String SSL_KEYSTORE_PASSW_KEY = "sslKeyStorePassword";
  public static final String SSL_KEYSTORE_PASSW_DEFAULT = "passkeystore";
  public static final String SSL_CA_TRUSTSTORE_KEY = "caTrustStore";
  public static final String SSL_CA_TRUSTSTORE_DEFAULT = "ca_truststore.bks";
  public static final String SSL_CERT_ALIAS_KEY = "certAlias";
  public static final String SSL_CERT_ALIAS_DEFAULT = "cert";
  public static final String SSL_CERT_PASSW_KEY = "certPassword";
  public static final String SSL_CERT_PASSW_DEFAULT = "passclient";
  public static final String ASYMMETRIC_ALG_KEY = "asymmetricAlg";
  public static final String ASYMMETRIC_ALG_DEFAULT = "RSA";
  public static final String SYMMETRIC_ALG_KEY = "symmetricAlg";
  public static final String SYMMETRIC_ALG_DEFAULT = "AES";
  public static final String SYMMETRIC_ALG_KEY_SIZE_KEY = "symmetricKeySize";
  public static final String SYMMETRIC_ALG_KEY_SIZE_DEFAULT = "256";


  /*********** SERVER SIDE ************/
  public static final String RAPID_FOLDER_SERVER_KEY = "rapidServerFolder";
  public static final String RAPID_FOLDER_SERVER_DEFAULT = "rapid-server";
  // Check if the method is offloaded or if it's running on client side.
  public static final String FILE_OFFLOADED =
      RAPID_FOLDER_SERVER_DEFAULT + File.separator + "offloaded";
  public static final String CLONE_ID_FILE =
      RAPID_FOLDER_SERVER_DEFAULT + File.separator + "cloneId";
  public static final String AS_PORT_KEY = "asPort";
  public static final String AS_PORT_SSL_KEY = "asPortSSL";
  // SLAM runs in the same machine as the VM
  public static final String SLAM_IP_KEY = "slamIp";
  public static final String SLAM_IP_DEFAULT = "127.0.0.1";
  public static final String SLAM_PORT_KEY = "slamPort";

  /*********** CLIENT SIDE ************/
  public static final String RAPID_FOLDER_CLIENT_KEY = "rapidClientFolder";
  public static final String RAPID_FOLDER_CLIENT_DEFAULT = "rapid-client";
  public static final String FILE_DB_CACHE =
      RAPID_FOLDER_CLIENT_DEFAULT + File.separator + "dbCache-";
  public static final String AC_RM_PORT_KEY = "acRmPort";
  public static final String CONNECT_PREV_VM_KEY = "connectToPrevVm";
  public static final String CONNECT_PREV_VM_DEFAULT = FALSE;
  public static final String CONNECT_SSL_KEY = "connectSSL";
  public static final String CONNECT_SSL_DEFAULT = FALSE;
  public static final String SHARED_PREFS_KEY = "sharedPrefs";
  public static final String SHARED_PREFS_DEFAULT = "sharedPrefs.properties";
  public static final String QOS_FILENAME = "rapid-qos.xml";

  private Constants() {
    throw new AssertionError();
  }
}
