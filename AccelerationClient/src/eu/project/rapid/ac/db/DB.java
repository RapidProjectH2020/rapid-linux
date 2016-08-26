package eu.project.rapid.ac.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import eu.project.rapid.utils.Configuration;

/**
 * Singleton DB class.<br>
 * TODO: For the moment the database 'rapidDb' and the user with permissions to access it has to be
 * created manually by someone before running the program. Implement the db and user creation here.
 * 
 * @author sokol
 *
 */
public class DB {
  public static String DB_DRIVER;
  public static String DB_URL;
  public static String DB_NAME;
  public static String DB_USER;
  public static String DB_PASS;
  public static final String LOG_TABLE = "logTable";
  public static final String KEY_APP_NAME = "appName";
  public static final String KEY_METHOD_NAME = "methodName";
  public static final String KEY_EXEC_LOCATION = "execLocation";
  public static final String KEY_NETWORK_TYPE = "networkType";
  public static final String KEY_RTT = "rtt";
  public static final String KEY_UL_RATE = "ulRate";
  public static final String KEY_DL_RATE = "dlRate";
  public static final String KEY_PREPARE_DATA_DURATION = "prepareDuration";
  public static final String KEY_EXEC_DURATION = "execDuration";
  public static final String KEY_PURE_EXEC_DURATION = "pureExecDuration";
  public static final String KEY_TIMESTAMP = "timestamp";
  public static final String TEXT = "TEXT";
  public static final String TEXT_NOT_NULL = "TEXT NOT NULL";

  private static DB db;
  private static Connection conn = null; // Connection to the db
  private static Statement stmt = null; // To run the sql statements
  private boolean connected;

  private static final Logger log = LogManager.getLogger(DB.class.getName());

  private DB(Configuration config) {
    DB_DRIVER = config.getDbDriver();
    DB_URL = config.getDbUrl();
    DB_NAME = config.getRapidDbName();
    DB_USER = config.getDbUser();
    DB_PASS = config.getDbPass();

    // Register JDBC driver
    try {
      // TimeZone timeZone = TimeZone.getTimeZone("Europe/Rome");
      // TimeZone.setDefault(timeZone);
      Class.forName("com.mysql.jdbc.Driver");
      // STEP 3: Open a connection
      log.info("Connecting to database...");
      conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
      // log.info("dbUser: " + DB_USER + " " + "rapidDbUser".equals(DB_USER));
      // log.info("dbPass: " + DB_PASS + " " + "rapidDbPass".equals(DB_PASS));

      if (conn != null) {
        // Check if our db exists, otherwise we will create it.
        if (!checkDbExists()) {
          createDb();
        }
        conn.close();

        // Connect to the rapid db now
        conn = DriverManager.getConnection(DB_URL + DB_NAME, DB_USER, DB_PASS);
        // To run the sql queries
        stmt = conn.createStatement();
        if (!checkTableExists()) {
          log.info("Creating table...");
          createTable();
        }
        // Now we have a DB with the table
        connected = true;
      }
    } catch (ClassNotFoundException e) {
      log.error("Could not connect to sql driver: " + e);
    } catch (SQLException e) {
      log.error("SQLException: " + e);
      e.printStackTrace();
    }
  }

  /**
   * @param config The configuration file created by the DFE
   * @return The db object if already exists, otherwise create it first.
   */
  public synchronized static DB getInstance(Configuration config) {
    if (db == null) {
      db = new DB(config);
    }
    return db;
  }

  /**
   * Check if our db exists, otherwise we will create it.
   * 
   * @return true if the db exists, false otherwise.
   */
  private static boolean checkDbExists() {
    try (ResultSet rs = conn.getMetaData().getCatalogs()) {
      while (rs.next()) {
        String tempDbName = rs.getString(1);
        if (tempDbName.equals(DB_NAME)) {
          log.info("The DB exists, no need to create it");
          return true;
        }
      }
    } catch (SQLException e) {
      log.error("Error with DB while checking if our db exists: " + e);
    }

    return false;
  }

  private static boolean checkTableExists() throws SQLException {
    DatabaseMetaData dbm = conn.getMetaData();
    ResultSet rs = dbm.getTables(null, null, LOG_TABLE, null);
    if (rs.next()) {
      log.info("Table exists");
      return true;
    } else {
      log.info("Table does not exist");
      return false;
    }
  }

  private static void createDb() throws SQLException {
    // To run the sql queries
    try (Statement stmt = conn.createStatement()) {
      // Create the DB
      String sql = "CREATE DATABASE " + DB_NAME;
      stmt.executeUpdate(sql);
      log.info(String.format("Database %s created", DB_NAME));
    }
  }

  private static void createTable() throws SQLException {
    String sql = String.format(
        "CREATE TABLE %s (%s %s, %s %s, %s %s, %s %s, %s %s, %s %s, %s %s, %s %s, %s %s, %s %s, %s %s)",
        LOG_TABLE, KEY_APP_NAME, TEXT_NOT_NULL, KEY_METHOD_NAME, TEXT_NOT_NULL, KEY_EXEC_LOCATION,
        TEXT_NOT_NULL, KEY_NETWORK_TYPE, TEXT, KEY_RTT, TEXT, KEY_UL_RATE, TEXT, KEY_DL_RATE, TEXT,
        KEY_PREPARE_DATA_DURATION, TEXT, KEY_EXEC_DURATION, TEXT_NOT_NULL, KEY_PURE_EXEC_DURATION,
        TEXT, KEY_TIMESTAMP, TEXT_NOT_NULL);

    // log.info(sql);
    stmt.executeUpdate(sql);
  }

  /**
   * Insert a row in the DB
   * 
   * @throws SQLException
   */
  public synchronized void insertEntry(List<String> keys, List<String> values) throws SQLException {
    // INSERT INT LOG_TABLE (key1, key2) VALUES ('val1', 'val2')
    String query = "INSERT INTO " + LOG_TABLE + " (";
    for (int i = 0; i < keys.size(); i++) {
      query += keys.get(i);
      if (i < keys.size() - 1) {
        query += ", ";
      }
    }

    query += ") VALUES (";

    for (int i = 0; i < values.size(); i++) {
      query += "'" + values.get(i) + "'";
      if (i < values.size() - 1) {
        query += ", ";
      }
    }
    query += ")";

    stmt.executeUpdate(query);
  }

  public ResultSet getAllEntries(String query) throws SQLException {
    // log.debug(query);
    return stmt.executeQuery(query);
  }

  public static void close() {
    if (stmt != null) {
      try {
        stmt.close();
      } catch (SQLException e) {
        log.error("Error while closing db stmt: " + e);
      }
    }

    if (conn != null) {
      try {
        conn.close();
      } catch (SQLException e) {
        log.error("Error while closing db: " + e);
      }
    }
  }

  /**
   * @return the connected
   */
  public boolean isConnected() {
    return connected;
  }

  /**
   * @param connected the connected to set
   */
  public void setConnected(boolean connected) {
    this.connected = connected;
  }
}
