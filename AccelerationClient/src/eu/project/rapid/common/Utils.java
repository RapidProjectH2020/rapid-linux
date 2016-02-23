package eu.project.rapid.common;

import java.io.File;
import java.io.FileNotFoundException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class Utils {

  private final static Logger log = LogManager.getLogger(Utils.class.getSimpleName());

  // The operating system where this app running on.
  private static String OS = null;

  /**
   * Create the rapid working directory.
   * 
   * @param parentDir The full path where to create the directory.
   * @param dirName The name of the new directory to be created.
   * @throws FileNotFoundException If the parent directory doesn't exist.
   */
  public static void createDirIfNotExist(String parentDir, String dirName)
      throws FileNotFoundException {
    File f = new File(parentDir);
    if (!f.exists()) {
      log.error("Can't create folder on path: " + parentDir + ", folder doesn't exist.");
      throw new FileNotFoundException();
    }

    f = new File(parentDir, dirName);
    if (!f.exists()) {
      log.warn("Creating folder " + dirName + " on " + parentDir);
      f.mkdir();
    } else {
      log.warn(dirName + " folder already exists on " + parentDir);
    }
  }

  /**
   * Create a directory on the given path if it doesn't already exist.
   * 
   * @param dirFullPath The full path of the directory to create.
   * @throws FileNotFoundException
   */
  public static void createDirIfNotExist(String dirFullPath) throws FileNotFoundException {
    createDirIfNotExist(dirFullPath.substring(0, dirFullPath.lastIndexOf(File.separator)),
        dirFullPath.substring(dirFullPath.lastIndexOf(File.separator) + 1));
  }

  /**
   * Create a sentinel file that methods can use to know that they have been offloaded.
   */
  public static void createOffloadFile() {
    log.info("Creating sentinel file on user's home: " + System.getProperty("user.home"));
  }

  /**
   * Recursively deletes a directory.
   * 
   * @param file the directory to delete.
   */
  public static void deleteDir(File file) {

    if (file.isDirectory()) {
      for (File f : file.listFiles()) {
        deleteDir(f);
      }
    }
    file.delete();
  }

  /**
   * @return the OS in lower case letters
   */
  public static String getOS() {
    if (OS == null) {
      OS = System.getProperty("os.name").toLowerCase();
    }
    return OS;
  }

  public static boolean isWindows() {
    return getOS().indexOf("win") >= 0;
  }

  public static boolean isMac() {
    return getOS().indexOf("mac") >= 0;
  }

  public static boolean isLinux() {
    return getOS().indexOf("nix") >= 0 || getOS().indexOf("nux") >= 0;
  }
}
