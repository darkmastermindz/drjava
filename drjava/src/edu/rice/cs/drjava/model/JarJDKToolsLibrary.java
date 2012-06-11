/*BEGIN_COPYRIGHT_BLOCK
 *
 * Copyright (c) 2001-2010, JavaPLT group at Rice University (drjava@rice.edu)
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *    * Redistributions of source code must retain the above copyright
 *      notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above copyright
 *      notice, this list of conditions and the following disclaimer in the
 *      documentation and/or other materials provided with the distribution.
 *    * Neither the names of DrJava, the JavaPLT group, Rice University, nor the
 *      names of its contributors may be used to endorse or promote products
 *      derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * This software is Open Source Initiative approved Open Source Software.
 * Open Source Initative Approved is a trademark of the Open Source Initiative.
 * 
 * This file is part of DrJava.  Download the current version of this project
 * from http://www.drjava.org/ or http://sourceforge.net/projects/drjava/
 * 
 * END_COPYRIGHT_BLOCK*/

package edu.rice.cs.drjava.model;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import java.util.jar.JarFile;
import java.util.jar.JarEntry;
import java.util.jar.Manifest;
import java.util.Enumeration;
import java.io.IOException;
import java.io.FileNotFoundException;

import edu.rice.cs.plt.io.IOUtil;
import edu.rice.cs.plt.iter.IterUtil;
import edu.rice.cs.plt.lambda.Lambda;
import edu.rice.cs.plt.lambda.LambdaUtil;
import edu.rice.cs.plt.lambda.Predicate;
import edu.rice.cs.plt.reflect.ReflectUtil;
import edu.rice.cs.plt.reflect.PathClassLoader;
import edu.rice.cs.plt.reflect.ShadowingClassLoader;
import edu.rice.cs.plt.reflect.PreemptingClassLoader;
import edu.rice.cs.plt.reflect.ReflectException;
import edu.rice.cs.plt.reflect.JavaVersion;
import edu.rice.cs.plt.reflect.JavaVersion.FullVersion;

import edu.rice.cs.drjava.model.compiler.CompilerInterface;
import edu.rice.cs.drjava.model.compiler.NoCompilerAvailable;
import edu.rice.cs.drjava.model.debug.Debugger;
import edu.rice.cs.drjava.model.debug.NoDebuggerAvailable;
import edu.rice.cs.drjava.model.javadoc.JavadocModel;
import edu.rice.cs.drjava.model.javadoc.DefaultJavadocModel;
import edu.rice.cs.drjava.model.javadoc.NoJavadocAvailable;
import edu.rice.cs.drjava.model.JDKDescriptor;

import edu.rice.cs.util.FileOps;

/** A JDKToolsLibrary that was loaded from a specific jar file. */
public class JarJDKToolsLibrary extends JDKToolsLibrary {
  
  /** Packages to shadow when loading a new tools.jar.  If we don't shadow these classes, we won't
    * be able to load distinct versions for each tools.jar library.  These should be verified whenever
    * a new Java version is released.  (We can't just shadow *everything* because some classes, at 
    * least in OS X's classes.jar, can only be loaded by the JVM.)
    */
  private static final Set<String> TOOLS_PACKAGES = new HashSet<String>();
  static {
    Collections.addAll(TOOLS_PACKAGES, new String[] {
      // From 1.4 tools.jar:
      "com.sun.javadoc",
      "com.sun.jdi",
      "com.sun.tools",
      "sun.applet", // also bundled in rt.jar
      "sun.rmi.rmic",
      //"sun.security.tools", // partially bundled in rt.jar -- it's inconsistent between versions, so we need to
                              // allow these classes to be loaded.  Hopefully this doesn't break anything.
      "sun.tools", // sun.tools.jar, sun.tools.hprof, and (sometimes) sun.tools.util.CommandLine are also in rt.jar
    
      // Additional from 5 tools.jar:
      "com.sun.jarsigner",
      "com.sun.mirror",
      "sun.jvmstat",
    
      // Additional from 6 tools.jar:
      "com.sun.codemodel",
      "com.sun.istack.internal.tools", // other istack packages are in rt.jar
      "com.sun.istack.internal.ws",
      "com.sun.source",
      "com.sun.xml.internal.dtdparser", // other xml.internal packages are in rt.jar
      "com.sun.xml.internal.rngom",
      "com.sun.xml.internal.xsom",
      "org.relaxng",
    });
  }
  
  /* Fields */
  private final File _location;
  private final List<File> _bootClassPath; // may be null (i.e. compiler's internal behavior)
  
  private JarJDKToolsLibrary(File location, FullVersion version, JDKDescriptor jdkDescriptor,
                             CompilerInterface compiler, Debugger debugger,
                             JavadocModel javadoc, List<File> bootClassPath) {
    super(version, jdkDescriptor, compiler, debugger, javadoc);
    _location = location;
    _bootClassPath = bootClassPath;
  }
  
  public File location() { return _location; }
  public List<File> bootClassPath() { // may be null
    if (_bootClassPath != null) return new ArrayList<File>(_bootClassPath);
    else return null;
  }
  
  public String toString() {
    return super.toString() + " at " + _location + ", boot classpath: " + bootClassPath();
  }

  /** Create a JarJDKToolsLibrary from a specific {@code "tools.jar"} or {@code "classes.jar"} file. */
  public static JarJDKToolsLibrary makeFromFile(File f, GlobalModel model, JDKDescriptor desc) {
    return makeFromFile(f, model, desc, new ArrayList<File>());
  }

  /** Create a JarJDKToolsLibrary from a specific {@code "tools.jar"} or {@code "classes.jar"} file. */
  public static JarJDKToolsLibrary makeFromFile(File f, GlobalModel model, JDKDescriptor desc,
                                                List<File> additionalBootClassPath) {
    assert desc != null;
    
    CompilerInterface compiler = NoCompilerAvailable.ONLY;
    Debugger debugger = NoDebuggerAvailable.ONLY;
    JavadocModel javadoc = new NoJavadocAvailable(model);
    
    FullVersion version = desc.guessVersion(f);
    JDKToolsLibrary.msg("makeFromFile: " + f + " --> " + version + ", vendor: " + version.vendor());
    JDKToolsLibrary.msg("    desc = " + desc);
    
    boolean isSupported = JavaVersion.CURRENT.supports(version.majorVersion());
    Iterable<File> additionalCompilerFiles = IterUtil.empty();

    // JDKDescriptor.NONE will require JavaVersion.CURRENT to be at least JavaVersion.JAVA_1_1,
    // i.e. it will always be supported
    isSupported |= JavaVersion.CURRENT.supports(desc.getMinimumMajorVersion());
    try {
      additionalCompilerFiles = desc.getAdditionalCompilerFiles(f);
    }
    catch(FileNotFoundException fnfe) {
      // not all additional compiler files were found
      isSupported = false;
    }
    
    // We can't execute code that was possibly compiled for a later Java API version.
    List<File> bootClassPath = null;
    if (isSupported) {
      // block tools.jar classes, so that references don't point to a different version of the classes
      ClassLoader loader =
        new ShadowingClassLoader(JarJDKToolsLibrary.class.getClassLoader(), true, TOOLS_PACKAGES, true);
      Iterable<File> path = IterUtil.map(IterUtil.compose(additionalCompilerFiles, f), new Lambda<File,File>() {
        public File value(File arg) { return IOUtil.attemptAbsoluteFile(arg); }
      });
      
      String compilerAdapter = desc.getAdapterForCompiler(version);
      
      if (compilerAdapter != null) {
        // determine boot class path
        File libDir = null;
        if (f.getName().equals("classes.jar")) { libDir = f.getParentFile(); }
        else if (f.getName().equals("tools.jar")) {
          File jdkLibDir = f.getParentFile();
          if (jdkLibDir != null) {
            File jdkRoot = jdkLibDir.getParentFile();
            if (jdkRoot != null) {
              File jreLibDir = new File(jdkRoot, "jre/lib");
              if (IOUtil.attemptExists(new File(jreLibDir, "rt.jar"))) { libDir = jreLibDir; }
            }
            if (libDir == null) {
              if (IOUtil.attemptExists(new File(jdkLibDir, "rt.jar"))) { libDir = jdkLibDir; }
            }
          }
        }
        bootClassPath = new ArrayList<File>();
        if (libDir != null) {
          File[] jars = IOUtil.attemptListFiles(libDir, IOUtil.extensionFilePredicate("jar"));
          if (jars != null) { bootClassPath.addAll(Arrays.asList(jars)); }
        }
        else {
          // could not determine boot classpath because the file was not named classes.jar or tools.jar
          // at least put the compiler file itself and the additional compiler files on the boot classpath
          bootClassPath.add(f);
          for(File acf: additionalCompilerFiles) { bootClassPath.add(acf); };
        }
        if (additionalBootClassPath != null) { bootClassPath.addAll(additionalBootClassPath); }
        if (bootClassPath.isEmpty()) { bootClassPath = null; } // null defers to the compiler's default behavior

        try {
          Class<?>[] sig = { FullVersion.class, String.class, List.class };
          Object[] args = { version, f.toString(), bootClassPath };
          // JDKToolsLibrary._log.log("classpath for compiler: "+IterUtil.multilineToString(path));
          // JDKToolsLibrary._log.log("boot classpath for compiler: "+IterUtil.multilineToString(bootClassPath));                
          CompilerInterface attempt = (CompilerInterface) ReflectUtil.loadLibraryAdapter(loader, path, compilerAdapter, 
                                                                                         sig, args);
          if (attempt.isAvailable()) { compiler = attempt; }
        }
        catch (ReflectException e) { /* can't load */ }
        catch (LinkageError e) { /* can't load */ }
      }
      
      String debuggerAdapter = desc.getAdapterForDebugger(version);
      String debuggerPackage = "edu.rice.cs.drjava.model.debug.jpda";
      if (debuggerAdapter != null) {
        try {
          JDKToolsLibrary.msg("                 loading debugger: "+debuggerAdapter);
          Class<?>[] sig = { GlobalModel.class };
          // can't use loadLibraryAdapter because we need to preempt the whole package
          ClassLoader debugLoader = new PreemptingClassLoader(new PathClassLoader(loader, path), debuggerPackage);
          Debugger attempt = (Debugger) ReflectUtil.loadObject(debugLoader, debuggerAdapter, sig, model);        
          JDKToolsLibrary.msg("                 debugger=" + attempt.getClass().getName());
          if (attempt.isAvailable()) { debugger = attempt; }
        }
        catch (ReflectException e) {
          JDKToolsLibrary.msg("                 no debugger, ReflectException " + e); /* can't load */
        }
        catch (LinkageError e) {
          JDKToolsLibrary.msg("                 no debugger, LinkageError " + e);  /* can't load */
        }
      }
      
      try {
        new PathClassLoader(loader, path).loadClass("com.sun.tools.javadoc.Main");
        File bin = new File(f.getParentFile(), "../bin");
        if (!IOUtil.attemptIsDirectory(bin)) { bin = new File(f.getParentFile(), "../Home/bin"); }
        if (!IOUtil.attemptIsDirectory(bin)) { bin = new File(System.getProperty("java.home", f.getParent())); }
        javadoc = new DefaultJavadocModel(model, bin, path);
      }
      catch (ClassNotFoundException e) { /* can't load */ }
      catch (LinkageError e) { /* can't load (probably not necessary, but might as well catch it) */ }
        
    }
    
    return new JarJDKToolsLibrary(f, version, desc, compiler, debugger, javadoc, bootClassPath);
  }
  
  public static FullVersion guessVersion(File f, JDKDescriptor desc) {
    assert desc != null;

    FullVersion result = null;    
    boolean forceUnknown = desc.isCompound();
    
    // We could start with f.getParentFile(), but this simplifies the logic
    File current = IOUtil.attemptCanonicalFile(f);
    String name;  // promoted outward for logging purposes
    String path;  // promoted outward for logging purposes
    String parsedVersion = "";
    String vendor = "";
    do {
      name = current.getName();
      path = current.getAbsolutePath();
      if (! forceUnknown) {
        if (path.startsWith("/System/Library/Frameworks/JavaVM.framework") || path.startsWith("/Library/Java")) vendor = "apple";
        else if (path.toLowerCase().contains("openjdk")) vendor = "openjdk";
        else if (path.toLowerCase().contains("sun")) vendor = "sun";
        else if (path.toLowerCase().contains("oracle")) vendor = "oracle";
      }
      if (name.startsWith("jdk-")) {
        parsedVersion = name.substring(4);
        result = JavaVersion.parseFullVersion(parsedVersion, vendor, vendor, f);
      }
      else if (name.startsWith("jdk")) {
        parsedVersion = name.substring(3); 
        result = JavaVersion.parseFullVersion(parsedVersion, vendor, vendor, f);
      }
      else if (name.startsWith("j2sdk") || name.startsWith("java-")) {
        parsedVersion = name.substring(5);
        result = JavaVersion.parseFullVersion(parsedVersion, vendor, vendor, f);
      }
      else if (name.matches("\\d+\\.\\d+\\.\\d+.*")) {  // The \d+ fields actually match single digits; .* matches an arbitrary suffix
        _log.log("Invoking parseFullVersion on " + name + ", " + vendor + ", " + vendor + ", " + f);  //Strip off 1.x where x is 6 or 7
        parsedVersion = name.substring(0, 5);  // could be generalized to multi-digit matches by using index of first char in .* instead of 5
        result = JavaVersion.parseFullVersion(parsedVersion, vendor, vendor, f);
        _log.log("Result is: " + result.versionString());
      }
      current = current.getParentFile();
    } while (current != null && result == null);
    
    if (result == null || result.majorVersion().equals(JavaVersion.UNRECOGNIZED) ||
        result.majorVersion().equals(JavaVersion.FUTURE)) {
      JarFile jf = null;
      try {
        jf = new JarFile(f);
        Manifest mf = jf.getManifest();
        if (mf != null) {
          String v = mf.getMainAttributes().getValue("Created-By");
          if (v != null) {
            int space = v.indexOf(' ');
            if (space >= 0) v = v.substring(0,space);
            result = JavaVersion.parseFullVersion(parsedVersion = v, vendor, vendor, f);
          }
        }
        
        // still unknown or future
        if (result == null || result.majorVersion().equals(JavaVersion.UNRECOGNIZED) ||
            result.majorVersion().equals(JavaVersion.FUTURE)) {
          // look for the first class file
          Enumeration<JarEntry> jes = jf.entries();
          while(jes.hasMoreElements()) {
            JarEntry je = jes.nextElement();
            if (je.getName().endsWith(".class")) {
              result = JavaVersion.parseClassVersion(jf.getInputStream(je)).fullVersion();
              break;
            }
          }
        }
      }
      catch(IOException ioe) { result = null; }
      finally {
        try {
          if (jf != null) jf.close();
        }
        catch(IOException ioe) { /* ignore, just trying to close the file */ }
      }
     
      if (result == null || result.majorVersion().equals(JavaVersion.UNRECOGNIZED)) {
        // Couldn't find a good version number, so we'll just guess that it's the currently-running version
        // Useful where the tools.jar file is in an unusual custom location      
        result = JavaVersion.CURRENT_FULL;
      }
      
      parsedVersion = result.versionString();
    }
    
    if ((result == null) || (result.vendor() == JavaVersion.VendorType.UNKNOWN)) {
      if (! forceUnknown) {
        if (result.majorVersion().compareTo(JavaVersion.JAVA_6) < 0) {
          // Java 5 or earlier, assume Sun
          vendor = "sun";
        }
        else {
          // distinguish Sun Java 6 and OpenJDK 6 if it is still unknown
          JarFile jf = null;
          try {
            jf = new JarFile(f);
            /* if (jf.getJarEntry("com/sun/tools/javac/file/JavacFileManager.class")!=null) {            
             // NOTE: this may cause OpenJDK 7 to also be recognized as sun
             vendor = "sun";
             }
             else */ if (jf.getJarEntry("com/sun/tools/javac/util/JavacFileManager.class")!=null) {
               vendor = "openjdk";
             }
             else if (jf.getJarEntry("com/sun/tools/javac/util/DefaultFileManager.class")!=null) {
               vendor = "sun";
             }
          }
          catch(IOException ioe) { /* keep existing version */ }
          finally {
            try {
              if (jf != null) jf.close();
            }
            catch(IOException ioe) { /* ignore, just trying to close the file */ }
          }
        }
      }
      result = JavaVersion.parseFullVersion(parsedVersion, vendor, vendor,f);
    }
    JDKToolsLibrary.msg("Guessed version for " + path + " is " + result.versionString());
    return result;
  }
  
  /** return a collection with the default roots. */
  protected static LinkedHashMap<File,Set<JDKDescriptor>> getDefaultSearchRoots() {
    JDKToolsLibrary.msg("---- Getting Default Search Roots ----");
    
    /* roots is a list of possible parent directories of Java installations and Java-based conpound pinstallations; 
     * we want to eliminate duplicates & remember insertion order
     */
    LinkedHashMap<File,Set<JDKDescriptor>> roots = new LinkedHashMap<File,Set<JDKDescriptor>>();
    
    String javaHome = System.getProperty("java.home");
    String envJavaHome = null;
    String envJava7Home = null;
    String programFiles = null;
    String systemDrive = null;
    if (JavaVersion.CURRENT.supports(JavaVersion.JAVA_5)) {
      // System.getenv is deprecated under 1.3 and 1.4, and may throw a java.lang.Error (!),
      // which we'd rather not have to catch
      envJavaHome = System.getenv("JAVA_HOME");
      programFiles = System.getenv("ProgramFiles");
      systemDrive = System.getenv("SystemDrive");
    }
   
    // unwind out of potential JRE subdirectory
    if (javaHome != null) {
      addIfDir(new File(javaHome), roots);
      addIfDir(new File(javaHome, ".."), roots);
      addIfDir(new File(javaHome, "../.."), roots);
      addIfDir(new File(javaHome, "../../.."), roots);
      addIfDir(new File(javaHome, "../../../.."), roots);
    }
    
    // add JAVA environment bindings to roots
    if (envJavaHome != null) {
      addIfDir(new File(envJavaHome), roots);
      addIfDir(new File(envJavaHome, ".."), roots);
      addIfDir(new File(envJavaHome, "../.."), roots);
    }
    
    // Add ProgramFiles to roots
    if (programFiles != null) {
      addIfDir(new File(programFiles, "Java"), roots);
      addIfDir(new File(programFiles), roots);
    }
    
    addIfDir(new File("/C:/Program Files/Java"), roots);
    addIfDir(new File("/C:/Program Files"), roots);
    
    if (systemDrive != null) {
      addIfDir(new File(systemDrive, "Java"), roots);
      addIfDir(new File(systemDrive), roots);
    }
    addIfDir(new File("/C:/Java"), roots);
    addIfDir(new File("/C:"), roots);
    
    /* Scala entries for Windows */
    addIfDir(new File("/C:/Scala/scala-2.9.1.final"), roots);
    addIfDir(new File("/C:/Scala/scala-2.9.1-1"), roots);
    
    /* Entries for Mac OS X */
    addIfDir(new File("/System/Library/Java/JavaVirtualMachines"), roots);
    addIfDir(new File("/Library/Java/JavaVirtualMachines"), roots);

    addIfDir(new File("/usr/java"), roots);
    addIfDir(new File("/usr/j2se"), roots);
    addIfDir(new File("/usr"), roots);
    addIfDir(new File("/usr/local/java"), roots);
    addIfDir(new File("/usr/local/j2se"), roots);
    addIfDir(new File("/usr/local"), roots);

    /* Entries for Linux java packages */
    addIfDir(new File("/usr/lib/jvm"), roots);
    addIfDir(new File("/usr/lib/jvm/java-7-oracle"), roots);
    addIfDir(new File("/usr/lib/jvm/java-7-openjdk"), roots);
    addIfDir(new File("/usr/lib/jvm/java-6-sun"), roots);
    addIfDir(new File("/usr/lib/jvm/java-6-openjdk"), roots);
    addIfDir(new File("/usr/lib/jvm/java-1.5.0-sun"), roots);

    /* Scala entries for Linux */
    addIfDir(new File("/usr/share/scala"), roots);
    addIfDir(new File("/opt/scala"), roots);
    
    return roots;
  }
  
  /* Search for tools.jar/classes.jar files in roots and, if found, transfer them to the jars collection. */
  protected static void searchRootsForJars(LinkedHashMap<File,Set<JDKDescriptor>> roots,
                                           LinkedHashMap<File,Set<JDKDescriptor>> jars) {
    _log.log("***** roots = " + roots);
    _log.log("***** jars = " + jars);
    // matches: starts with "j2sdk", starts with "jdk", has form "[number].[number].[number]" (OS X), or
    // starts with "java-" (Linux)
    Predicate<File> subdirFilter = LambdaUtil.or(IOUtil.regexCanonicalCaseFilePredicate("j2sdk.*"),
                                                 IOUtil.regexCanonicalCaseFilePredicate("jdk.*"),
                                                 LambdaUtil.or(IOUtil.regexCanonicalCaseFilePredicate("\\d+\\.\\d+\\.\\d+.*"),
                                                               IOUtil.regexCanonicalCaseFilePredicate("java-.*"))); 
    for (Map.Entry<File,Set<JDKDescriptor>> root : roots.entrySet()) {
      JDKToolsLibrary.msg("Searching root (for jar files): " + root.getKey());
      for (File subdir : IOUtil.attemptListFilesAsIterable(root.getKey(), subdirFilter)) {
        JDKToolsLibrary.msg("Looking at subdirectory: " + subdir);
        addIfFile(new File(subdir, "lib/tools.jar"), root.getValue(), jars);
        addIfFile(new File(subdir, "Classes/classes.jar"), root.getValue(), jars);
        addIfFile(new File(subdir, "Contents/Classes/classes.jar"), root.getValue(), jars);
        addIfFile(new File(subdir, "Contents/Home/lib/tools.jar"), root.getValue(), jars);
      }
    }
  }
  
  /** Check which jars are valid JDKs, and determine if they are compound or full (non-compound) JDKs. */
  protected static void collectValidResults(GlobalModel model,
                                            LinkedHashMap<File,Set<JDKDescriptor>> jars,
                                            Map<FullVersion, Iterable<JarJDKToolsLibrary>> results,
                                            Map<FullVersion, Iterable<JarJDKToolsLibrary>> compoundResults) {
    JDKToolsLibrary.msg("---- Collecting Valid Results ----");
    for (Map.Entry<File,Set<JDKDescriptor>> jar : jars.entrySet()) {
      for (JDKDescriptor desc : jar.getValue()) {
        assert desc != null;

        boolean containsCompiler = desc.containsCompiler(jar.getKey());
        JDKToolsLibrary.msg("Checking file " + jar.getKey() + " for " + desc);
        JDKToolsLibrary.msg("    " + containsCompiler);
        if (! containsCompiler) continue;

        JarJDKToolsLibrary lib = makeFromFile(jar.getKey(), model, desc);
        if (lib.isValid()) {
          FullVersion v = lib.version();
          Map<FullVersion, Iterable<JarJDKToolsLibrary>> mapToAddTo = results;
          if (desc.isCompound()) { mapToAddTo = compoundResults; }
          
          if (mapToAddTo.containsKey(v)) { mapToAddTo.put(v, IterUtil.compose(lib, mapToAddTo.get(v))); }
          else { mapToAddTo.put(v, IterUtil.singleton(lib)); }
        }
        else {
          JDKToolsLibrary.msg("    library is not valid: compiler=" + lib.compiler().isAvailable() +
                              " debugger=" + lib.debugger().isAvailable() + " javadoc=" + lib.javadoc().isAvailable());
        }
      }
    }
  }
  
  /** Get completed compound JDKs by going through the list of compound JDKs and finding full JDKs that
    * complete them. */
  protected static Map<FullVersion, Iterable<JarJDKToolsLibrary>>
    getCompletedCompoundResults(GlobalModel model, Iterable<JarJDKToolsLibrary> collapsed,
                                Iterable<JarJDKToolsLibrary> compoundCollapsed) {
    JDKToolsLibrary.msg("---- Getting Completed Compound Results ----");
    
    Map<FullVersion, Iterable<JarJDKToolsLibrary>> completedResults =
      new TreeMap<FullVersion, Iterable<JarJDKToolsLibrary>>();
    
    // now we have the JDK libraries in collapsed and the compound libraries in compoundCollapsed
    for(JarJDKToolsLibrary compoundLib: compoundCollapsed) {
      JDKToolsLibrary.msg("Checking compoundLib: " + compoundLib + " at location " + compoundLib.location());
      FullVersion compoundVersion = compoundLib.version();
      JarJDKToolsLibrary found = null;
      // try to find a JDK in results that matches compoundVersion exactly, except for vendor
      for(JarJDKToolsLibrary javaLib: collapsed) {
        if (! javaLib.jdkDescriptor().isBaseForCompound()) continue; // javaLib not suitable as base
        JDKToolsLibrary.msg("    exact? " + javaLib);  // Is exact comparison necessary?  It never seems to match.
        FullVersion javaVersion = javaLib.version();
        if ((javaVersion.majorVersion().equals(compoundVersion.majorVersion())) &&
            (javaVersion.maintenance() == compoundVersion.maintenance()) &&
            (javaVersion.update() == compoundVersion.update()) &&
            (javaVersion.supports(compoundLib.jdkDescriptor().getMinimumMajorVersion()))) {
          JDKToolsLibrary.msg("        found");
          found = javaLib;
          break;
        }
      }
      // if we didn't find one, take the best JDK that matches the major version
      if (found == null) {
        for(JarJDKToolsLibrary javaLib: collapsed) {
          if (! javaLib.jdkDescriptor().isBaseForCompound()) continue; // javaLib not suitable as base
          JDKToolsLibrary.msg("    major? " + javaLib);
          FullVersion javaVersion = javaLib.version();
          if (javaVersion.majorVersion().equals(compoundVersion.majorVersion()) &&
              javaVersion.supports(compoundLib.jdkDescriptor().getMinimumMajorVersion())) {
            JDKToolsLibrary.msg("        found");
            found = javaLib;
            break;
          }
        }
      }
      // if we found a JDK, then create a new compound library
      if (found != null) {
        JarJDKToolsLibrary lib = makeFromFile(compoundLib.location(), model, compoundLib.jdkDescriptor(),
                                              found.bootClassPath());
        if (lib.isValid()) {
          JDKToolsLibrary.msg("    based on version " + lib.version());
          FullVersion v = lib.version();
          if (completedResults.containsKey(v)) {
            completedResults.put(v, IterUtil.compose(lib, completedResults.get(v)));
          }
          else {
            completedResults.put(v, IterUtil.singleton(lib));
          }
        }
      }
    }
    return completedResults;
  }
  
  /** Produce a list of compilers discovered in the file system.  A variety of locations are searched;
    * only those files that can produce a valid library (see {@link #isValid} are returned.  The result is
    * sorted by version.  Where one library of the same version might be preferred over another, the preferred 
    * library appears earlier in the result list.
    */
  public static Iterable<JarJDKToolsLibrary> search(GlobalModel model) {
    JDKToolsLibrary.msg("---- Searching for Libraries ----");
    
    /* roots is a list of possible parent directories of Java installations; we want to eliminate duplicates & 
     * remember insertion order
     */
    LinkedHashMap<File,Set<JDKDescriptor>> roots = getDefaultSearchRoots();

    /* jars is a list of possible jar files containing standard Java or extended compilers; we want to eliminate 
     * duplicates & remember insertion order
     */
    LinkedHashMap<File,Set<JDKDescriptor>> jars = new LinkedHashMap<File,Set<JDKDescriptor>>();

    // Search for all JDK descriptors in the drjava.jar file or lib/platform.jar in the executable file tree
    Iterable<JDKDescriptor> descriptors = searchForJDKDescriptorsInExecutable(); 
    _log.log("***** Finished searching for JDKDescriptor classes *****  descriptors = " + descriptors);
    for (JDKDescriptor desc: descriptors) {
      // add the specific search directories and files
      _log.log("Processing descriptor " + desc);
      if (desc.toString().contains("Scala")) {
        _log.log("ScalaDescriptor.getSearchDirectories = " + desc.getSearchDirectories());
        _log.log("ScalaDescriptor.getSearchFiles = " + desc.getSearchFiles());
      }
      for (File f: desc.getSearchDirectories()) { addIfDir(f, desc, roots); }
      for (File f: desc.getSearchFiles()) { addIfFile(f, desc, jars); }
      // add to the set of packages that need to be shadowed
      TOOLS_PACKAGES.addAll(desc.getToolsPackages());
    }
    
    // search for jar files in roots and, if found, transfer them to the jars collection
    searchRootsForJars(roots, jars);

    // check which jars are valid JDKs, and determine if they are compound or full (non-compound) JDKs
    Map<FullVersion, Iterable<JarJDKToolsLibrary>> results = new TreeMap<FullVersion, Iterable<JarJDKToolsLibrary>>();
    Map<FullVersion, Iterable<JarJDKToolsLibrary>> compoundResults =
      new TreeMap<FullVersion, Iterable<JarJDKToolsLibrary>>();
    
    collectValidResults(model, jars, results, compoundResults);
    
    // We store everything in reverse order, since that's the natural order of the versions
    Iterable<JarJDKToolsLibrary> collapsed = IterUtil.reverse(IterUtil.collapse(results.values()));
        
    JDKToolsLibrary.msg("***** Found the following base libraries *****");
    for (JarJDKToolsLibrary lib: collapsed) {
      JDKToolsLibrary.msg("*  Base library: " + lib);
    }
    
    Iterable<JarJDKToolsLibrary> compoundCollapsed = IterUtil.reverse(IterUtil.collapse(compoundResults.values()));
    
    // Get completed compound JDKs by going through the list of compound JDKs and finding corresponding full JDKs 
    Map<FullVersion, Iterable<JarJDKToolsLibrary>> completedResults =
      getCompletedCompoundResults(model, collapsed, compoundCollapsed);

    JDKToolsLibrary.msg("***** Found the following completed compound libraries *****");
    for (JarJDKToolsLibrary lib: IterUtil.collapse(completedResults.values())) {
      JDKToolsLibrary.msg("*  Compound library: " + lib);
    }
    
    Iterable<JarJDKToolsLibrary> result = 
      IterUtil.compose(collapsed, IterUtil.reverse(IterUtil.collapse(completedResults.values())));

    return result;
  }
  
  /** Add a canonicalized File {@code f} to the given set if it is an existing directory or link */
  private static void addIfDir(File f, Map<? super File, Set<JDKDescriptor>> map) {
    addIfDir(f, JDKDescriptor.NONE, map);
  }
  
  /** Add JDKDescriptor c to the JDKDescriptor set for canonicalized directory {@code f} */
  private static void addIfDir(File f, JDKDescriptor c, Map<? super File, Set<JDKDescriptor>> map) {
    JDKToolsLibrary.msg("Adding JDKDescriptor " + c + " to set for " + f);
    f = IOUtil.attemptCanonicalFile(f);
    if (IOUtil.attemptIsDirectory(f)) {
      Set<JDKDescriptor> set = map.get(f);
      if (set == null) {
        set = new LinkedHashSet<JDKDescriptor>();
        map.put(f, set);
      }
      if (! set.contains(f)) JDKToolsLibrary.msg("JDKDescriptor " + c + " recorded for dir " + f);
      set.add(c);
    }
    else { JDKToolsLibrary.msg("Dir does not exist: " + f); }
  }
  
  //** Add {@code JDKDescriptor.NONE} to the JDKDescriptor set for file {@code f} */
  private static void addIfFile(File f, Map<? super File,Set<JDKDescriptor>> map) {
    addIfFile(f, JDKDescriptor.NONE, map);
  }
  
  
  //** Add JDKDescriptor {@code c} to the JDKDescriptor set for file {@code f} */
  private static void addIfFile(File f, JDKDescriptor c, Map<? super File,Set<JDKDescriptor>> map) {
    addIfFile(f, Collections.singleton(c), map);
  }

  //** Add JDKDescriptor set cs to the JDKDescriptor set for file {@code f} */
  private static void addIfFile(File f, Set<JDKDescriptor> cs, Map<? super File,Set<JDKDescriptor>> map) {
    f = IOUtil.attemptCanonicalFile(f);
    if (IOUtil.attemptIsFile(f)) {
      Set<JDKDescriptor> set = map.get(f);
      if (set == null) {
        set = new LinkedHashSet<JDKDescriptor>();
        map.put(f, set);
      }
      set.addAll(cs);
      JDKToolsLibrary.msg("Found JDKDescriptors " + cs + " for File " + f);
    }
    else { JDKToolsLibrary.msg("File not found: " + f); }
  }
  
  /** Search for JDK descriptors in drjava.jar (if that is executable) or ../../lib/plaform.jar (if executable
    * is file tree rooted at .../classes/base.
    */
  private static Iterable<JDKDescriptor> searchForJDKDescriptorsInExecutable() {
    JDKToolsLibrary.msg("---- Searching for JDKDescriptors in executable ----");
    long t0 = System.currentTimeMillis();
    JDKToolsLibrary.msg("ms: " + t0);
    Iterable<JDKDescriptor> descriptors = null;  // init required by compiler
    try {
      File f = FileOps.getDrJavaFile();
      JDKToolsLibrary.msg("DrJavaFile is: " + f);
      if (f.isFile()) { /* Not a directory; must be jar file drjava.jar including the scala compiler */
//         _log.log("Before searching jar file for DrJava, descriptors = " + descriptors);
        descriptors = searchJarFileForJDKDescriptors(new JarFile(f));
        _log.log("After searching jar file for DrJava, descriptors = " + descriptors);
      }
      else {
        JDKToolsLibrary.msg("Searching class file tree corresponding to: " + f);
        File parent = f.getParentFile();
        final String PACKAGE = "edu/rice/cs/drjava/model/compiler/descriptors";
        final String DESC_PATH = "lib/" + PACKAGE;
        File dir = new File(parent, DESC_PATH);
        JDKToolsLibrary.msg("Root directory for descriptors is " + dir);
        Iterable<File> files = IOUtil.listFilesRecursively(dir, new Predicate<File>() {
          public boolean contains(File arg) {
            return (arg.isFile()) && arg.getName().endsWith(".class") && (arg.getName().indexOf('$') < 0);
          }
        });
        for (File je: files) {
          final String name = PACKAGE + "/" + je.getName();
          descriptors = attemptToLoadDescriptor(descriptors, name);
          JDKToolsLibrary.msg("Found potential JDKDescriptor: " + name);
        }
      }
    }
    catch(IOException ioe) {
      /* ignore, just return the descriptors we have (which may be none) */
    }
    long t1 = System.currentTimeMillis();
    JDKToolsLibrary.msg("ms: "+t1);
    JDKToolsLibrary.msg("duration ms: "+(t1-t0));
    JDKToolsLibrary.msg("***** Done searching for descriptors ***** descriptors = " + descriptors);
    return descriptors;
  }
  
  /** Search JarFile jf for JDKDescriptor classes. */
  private static Iterable<JDKDescriptor> searchJarFileForJDKDescriptors(JarFile jf) {  
    JDKToolsLibrary.msg("Entering searchJarFileForJDKDescriptors; searching jar file " + jf.getName());
    Iterable<JDKDescriptor> descriptors = IterUtil.empty();
    Enumeration<JarEntry> entries = jf.entries();
    while (entries.hasMoreElements()) {
      JarEntry je = entries.nextElement();
      String name = je.getName();
      if (name.startsWith("edu/rice/cs/drjava/model/compiler/descriptors/") && name.endsWith(".class") &&
          (name.indexOf('$') < 0)) {
        descriptors = attemptToLoadDescriptor(descriptors, name);
        JDKToolsLibrary.msg("Found potential JDKDescriptor: " + name);
        JDKToolsLibrary.msg("descriptors = " + descriptors);
      }
    }
    JDKToolsLibrary.msg("Exiting searchJarFileForJDKDescriptors; descriptors = " + descriptors);
    return descriptors;
  }
  
  /** Attempt to load a JDK descriptor, append it to the list if succesful. */
  protected static Iterable<JDKDescriptor> attemptToLoadDescriptor(Iterable<JDKDescriptor> descriptors, String name) {
    int dotPos = name.indexOf(".class");
    String className = name.substring(0, dotPos).replace('/','.');
    _log.log("Attempting to load JDKDescriptor class '" + className + "'");
    try {
      JDKToolsLibrary.msg("    class name: " + className);
      Class<?> clazz = Class.forName(className);
      Class<? extends JDKDescriptor> descClass = clazz.asSubclass(JDKDescriptor.class);
      JDKDescriptor desc = descClass.newInstance();
      descriptors = IterUtil.compose(descriptors, desc);
      JDKToolsLibrary.msg("        loaded!");
    }
    catch(LinkageError le) { JDKToolsLibrary.msg("LinkageError: " + le); /* ignore */ } 
    catch(ClassNotFoundException cnfe) { JDKToolsLibrary.msg("ClassNotFoundException: " + cnfe); /* ignore */ }
    catch(ClassCastException cce) { JDKToolsLibrary.msg("ClassCastException: " + cce); /* ignore */ }
    catch(IllegalAccessException iae) { JDKToolsLibrary.msg("IllegalAccessException: " + iae); /* ignore */ }
    catch(InstantiationException ie) { JDKToolsLibrary.msg("InstantiationException: " + ie); /* ignore */ }
    
    return descriptors;
  }
}
