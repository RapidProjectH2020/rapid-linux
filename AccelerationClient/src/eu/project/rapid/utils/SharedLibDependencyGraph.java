package eu.project.rapid.utils;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * This graph represents the dependencies of shared objects (.so) that are used by a Java
 * application through JNI. A node represents a library. An arc from node l1 -> l2 represents the
 * fact that l1 depends on l2, which means that l2 should be installed before l1. To install all
 * libraries in the right dependency order, one can use the method
 * <code>getOneNonDependentLib</code> to find the first library that doesn't depend on any library.
 * This corresponds to a node that doesn't have any exit arc.
 * 
 * @author sokol
 *
 */
public class SharedLibDependencyGraph {

  private Map<String, List<String>> dependencies;

  public SharedLibDependencyGraph() {
    dependencies = new HashMap<>();
  }

  public void addLibrary(String libName) {
    if (!dependencies.containsKey(libName)) {
      dependencies.put(libName, new LinkedList<>());
    }
  }

  /**
   * If we are removing the library we are sure that no other library depends on this one.
   * 
   * @param libName the library to remove.
   */
  private void removeLibrary(String libName) {
    dependencies.remove(libName);
    for (String key : dependencies.keySet()) {
      dependencies.get(key).remove(libName);
    }
  }

  public void addDependency(String fromLib, String toLib) {
    addLibrary(fromLib);
    dependencies.get(fromLib).add(toLib);
  }

  /**
   * @return The first library that doesn't depend on other libraries.
   */
  public String getOneNonDependentLib() {
    String lib = null;
    for (String key : dependencies.keySet()) {
      if (dependencies.get(key).size() == 0) {
        lib = key;
      }
    }

    removeLibrary(lib);
    return lib;
  }
}

