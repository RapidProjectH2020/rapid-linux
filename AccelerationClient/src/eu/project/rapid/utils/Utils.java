package eu.project.rapid.utils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.Scanner;

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
    log.info("Creating sentinel file: " + Constants.FILE_OFFLOADED);
    File f = new File(Constants.FILE_OFFLOADED);
    try {
      f.createNewFile();
    } catch (IOException e) {
      log.error("Error while creating sentinel file: " + e);
    }
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

  /**
   * An empty file will be created automatically on the clone by Acceleration-Server. The presence
   * or absence of this file can let the method know if it is running on the phone or on the clone.
   * 
   * @return <b>True</b> if it is running on the clone<br>
   *         <b>False</b> if it is running on the phone.
   */
  public static boolean isOffloaded() {
    try {
      File tempFile = new File(Constants.FILE_OFFLOADED);
      return tempFile.exists();
    } catch (Exception e) {
      return true;
    }
  }

  /**
   * Read the file "/mnt/sdcard/rapid/cloneId" for the ID of this clone.
   * 
   * @return 0 if this is the phone or the main clone (the file may even not exist in these cases)
   *         <br>
   *         CLONE_ID otherwise
   */
  public static int readCloneHelperId() {
    Scanner cloneIdReader = null;
    int cloneId = 0;

    try {
      File cloneIdFile = new File(Constants.CLONE_ID_FILE);
      cloneIdReader = new Scanner(cloneIdFile);
      cloneId = cloneIdReader.nextInt();
    } catch (Exception e) {
      // Stay quiet, we know it.
    } finally {
      try {
        cloneIdReader.close();
      } catch (Exception e) {
        System.err.println(
            "CloneId file is not here, this means that this is the main clone (or the phone)");
      }
    }

    return cloneId;
  }

  /**
   * This utility method will be used to write an object on a file. The object can be a Set, a Map,
   * etc.<br>
   * The method creates a lock file (if it doesn't exist) and tries to get a <b>blocking lock</b> on
   * the lock file. After writing the object the lock is released, so that other processes that want
   * to read the file can access it by getting the lock.
   * 
   * @param filePath The full path of the file where to write the object. If the file exists it will
   *        first be deleted and then created from scratch.
   * @param obj The object to write on the file.
   * @throws IOException
   */
  public static void writeObjectToFile(String filePath, Object obj) throws IOException {
    // Get a lock on the lockFile so that concurrent DFEs don't mess with each other by
    // reading/writing the d2dSetFile.
    File lockFile = new File(filePath + ".lock");
    // Create a FileChannel that can read and write that file.
    // This will create the file if it doesn't exit.
    RandomAccessFile file = new RandomAccessFile(lockFile, "rw");
    FileChannel f = file.getChannel();

    // Try to get an exclusive lock on the file.
    // FileLock lock = f.tryLock();
    FileLock lock = f.lock();

    // Now we have the lock, so we can write on the file
    File outFile = new File(filePath);
    if (outFile.exists()) {
      outFile.delete();
    }
    outFile.createNewFile();
    FileOutputStream fout = new FileOutputStream(outFile);
    ObjectOutputStream oos = new ObjectOutputStream(fout);
    oos.writeObject(obj);
    oos.close();

    // Now we release the lock and close the lockFile
    lock.release();
    file.close();
  }

  /**
   * Reads the previously serialized object from the <code>filename</code>.<br>
   * This method will try to get a <b>non blocking lock</b> on a lock file.
   * 
   * @param filePath The full path of the file from where to read the object.
   * @return The serialized object previously written using the method
   *         <code>writeObjectToFile</code>
   * @throws IOException
   * @throws ClassNotFoundException
   */
  public static Object readObjectFromFile(String filePath)
      throws IOException, ClassNotFoundException {
    Object obj = null;

    // First try to get the lock on a lock file
    File lockFile = new File(filePath + ".lock");
    if (!lockFile.exists()) {
      // It means that no other process has written an object before.
      return null;
    }

    // Create a FileChannel that can read and write that file.
    // This will create the file if it doesn't exit.
    RandomAccessFile file = new RandomAccessFile(lockFile, "rw");
    FileChannel f = file.getChannel();

    // Try to get an exclusive lock on the file.
    // FileLock lock = f.tryLock();
    FileLock lock = f.lock();

    // Now we have the lock, so we can read from the file
    File inFile = new File(filePath);
    FileInputStream fis = new FileInputStream(inFile);
    ObjectInputStream ois = new ObjectInputStream(fis);
    obj = ois.readObject();
    ois.close();

    // Now we release the lock and close the lockFile
    lock.release();
    file.close();
    return obj;
  }

  public static byte[] objectToByteArray(Object o) throws IOException {
    byte[] bytes = null;
    ByteArrayOutputStream bos = null;
    ObjectOutputStream oos = null;
    try {
      bos = new ByteArrayOutputStream();
      oos = new ObjectOutputStream(bos);
      oos.writeObject(o);
      oos.flush();
      bytes = bos.toByteArray();
    } finally {
      if (oos != null) {
        oos.close();
      }
      if (bos != null) {
        bos.close();
      }
    }
    return bytes;
  }
}
