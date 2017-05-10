package eu.project.rapid.as;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.util.Hashtable;

public class RapidClassLoader extends ClassLoader {

    private final Logger log = LogManager.getLogger(RapidClassLoader.class.getSimpleName());

    private String appFolder; // Path to the app folder where the jar file will be extracted
    private Hashtable<String, Class<?>> classes = new Hashtable<>(); // used to cache already defined
    // classes

    public RapidClassLoader(String appFolder) {
        super(RapidClassLoader.class.getClassLoader()); // calls the parent class loader's constructor
        this.appFolder = appFolder;
    }

    public Class<?> loadClass(String className) throws ClassNotFoundException {
        return findClass(className);
    }

    public Class<?> findClass(String className) {
        // log.info("Inside findClass: " + className);
        byte[] classByte;
        Class<?> result = classes.get(className); // checks in cached classes
        if (result != null) {
            return result;
        }

        try {
            return findSystemClass(className);
        } catch (Exception e) {
            // e.printStackTrace();
        }

        try {
            classByte = loadClassFileData(
                    appFolder + File.separatorChar + className.replace('.', File.separatorChar) + ".class");
            result = defineClass(className, classByte, 0, classByte.length, null);
            classes.put(className, result);
        } catch (IOException e) {
            log.error("Exception while reading the class file: " + e);
        }
        return result;
    }

    /**
     * Reads the file (.class) into a byte array. The file should be accessible as a resource and make
     * sure that its not in Classpath to avoid any confusion.
     *
     * @param name File name
     * @return Byte array read from the file
     * @throws IOException if any exception comes in reading the file
     */
    private byte[] loadClassFileData(String name) throws IOException {

        log.info("Loading bytes of class: " + name);

        File classFile = new File(name);
        byte buff[] = new byte[(int) classFile.length()];
        InputStream stream = new FileInputStream(new File(name));
        DataInputStream in = new DataInputStream(stream);
        in.readFully(buff);
        in.close();
        return buff;
    }

    void setAppFolder(String appFolder) {
        this.appFolder = appFolder;
    }
}
