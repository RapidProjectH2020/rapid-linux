package eu.project.rapid.as;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;

public class DynamicObjectInputStream extends ObjectInputStream {
    private Logger log = LogManager.getLogger(DynamicObjectInputStream.class.getSimpleName());

    // private InputStream is;
    private RapidClassLoader rapidClassLoader;

    public DynamicObjectInputStream(InputStream in) throws IOException {
        super(in);
        //
        // this.is = in;
    }

    /**
     * Override the method resolving a class to also look into the constructed DexClassLoader
     */
    @Override
    protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException {
        // log.info("Resolving class: " + desc.getName());

        try {
            return super.resolveClass(desc);
        } catch (ClassNotFoundException e) {
            // log.warn("Could not load the class " + desc.getName() + " using the system classloader");

            if (rapidClassLoader != null) {
                Class<?> resolvedClass = rapidClassLoader.findClass(desc.getName());
                // log.info("Resolved class: " + resolvedClass);
                return resolvedClass;
            }
        }

        return null;
    }

    void setClassLoader(RapidClassLoader rapidClassLoader) {
        this.rapidClassLoader = rapidClassLoader;
    }

    ClassLoader getClassLoader() {
        return rapidClassLoader;
    }
}
