package probe;

import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import javassist.ClassPool;
import javassist.CtClass;

/** Why does JarTypeSolver fail in a native image? Isolate the layers: javassist I/O, then the solver index. */
public class JarDiag {

    public static void main(String[] args) {
        String mode = System.getProperty("org.graalvm.nativeimage.imagecode") != null ? "NATIVE IMAGE" : "JVM";
        System.out.println("==== JarDiag on: " + mode + " ====");
        String jar = args[0];
        String fqn = "com.github.javaparser.ParserConfiguration";

        System.out.println("[layer 1] javassist ClassPool reads the jar directly");
        try {
            ClassPool pool = new ClassPool(false);
            pool.appendClassPath(jar);
            CtClass ct = pool.get(fqn);
            System.out.println("  OK   javassist read " + ct.getName()
                    + ", declared methods: " + ct.getDeclaredMethods().length);
            try {
                System.out.println("  OK   superclass: " + ct.getSuperclass().getName());
            } catch (Throwable t) {
                System.out.println("  FAIL superclass: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        } catch (Throwable t) {
            System.out.println("  FAIL javassist: " + t.getClass().getName() + ": " + t.getMessage());
        }

        System.out.println("[layer 2] JarTypeSolver index lookup");
        try {
            JarTypeSolver ts = new JarTypeSolver(jar);
            var ref = ts.tryToSolveType(fqn);
            System.out.println("  isSolved=" + ref.isSolved());
            if (ref.isSolved()) {
                var decl = ref.getCorrespondingDeclaration();
                System.out.println("  OK   " + decl.getQualifiedName());
                try {
                    System.out.println("  OK   declared methods visible: " + decl.getDeclaredMethods().size());
                } catch (Throwable t) {
                    System.out.println("  FAIL getDeclaredMethods: " + t.getClass().getName() + ": " + t.getMessage());
                }
                try {
                    System.out.println("  OK   ancestors: " + decl.getAllAncestors().size());
                } catch (Throwable t) {
                    System.out.println("  FAIL getAllAncestors: " + t.getClass().getName() + ": " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            System.out.println("  FAIL JarTypeSolver: " + t.getClass().getName() + ": " + t.getMessage());
        }
    }
}
