package probe.shadow;

/** Packaged as com.github.javaparser:javaparser-core:3.28.2, which also sits in ~/.m2: whichever wins is on the classpath. */
public class NotJavaParser {
    public static String which() { return "the reader's javaparser-core, not ~/.m2's"; }
}
