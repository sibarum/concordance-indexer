package probe.app;

/** Compiles only if probe:served came from the reader and the reader's javaparser-core beat the one in ~/.m2. */
public class App {
    public static void main(String[] args) {
        System.out.println(probe.served.Hello.greet());
        System.out.println(probe.shadow.NotJavaParser.which());
    }
}
