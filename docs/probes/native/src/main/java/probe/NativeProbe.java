package probe;

import com.github.javaparser.*;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.*;
import java.nio.file.*;
import java.util.*;

/**
 * Runs the same resolution work on the JVM and inside a native image, so the two outputs can be diffed.
 *
 * argv: <corpusDir> [jar ...]
 */
public class NativeProbe {

    static int pass = 0, fail = 0;

    static void check(String label, MethodCallExpr call) {
        try {
            System.out.printf("  PASS  %-30s -> %s%n", label, call.resolve().getQualifiedSignature());
            pass++;
        } catch (Throwable t) {
            String msg = String.valueOf(t.getMessage()).replaceAll("\\s+", " ");
            System.out.printf("  FAIL  %-30s -> %s: %s%n", label, t.getClass().getSimpleName(),
                    msg.length() > 150 ? msg.substring(0, 150) + "..." : msg);
            fail++;
        }
    }

    static MethodCallExpr find(CompilationUnit cu, String name) {
        return cu.findAll(MethodCallExpr.class).stream()
                .filter(c -> c.getNameAsString().equals(name))
                .findFirst().orElseThrow(() -> new IllegalStateException("no call named " + name));
    }

    public static void main(String[] args) throws Exception {
        String mode = System.getProperty("org.graalvm.nativeimage.imagecode") != null ? "NATIVE IMAGE" : "JVM";
        System.out.println("==== running on: " + mode + " ====");

        Path corpus = Path.of(args[0]);
        List<String> jars = new ArrayList<>(Arrays.asList(args).subList(1, args.length));

        // --- solver 1: source only ---
        System.out.println("[JavaParserTypeSolver] source-to-source resolution");
        try {
            CombinedTypeSolver ts = new CombinedTypeSolver();
            ParserConfiguration cfg = new ParserConfiguration()
                    .setLanguageLevel(LanguageLevel.JAVA_25)
                    .setSymbolResolver(new JavaSymbolSolver(ts));
            ts.add(new JavaParserTypeSolver(corpus, cfg));
            JavaParser p = new JavaParser(cfg);
            CompilationUnit cu = p.parse(corpus.resolve("Alpha.java")).getResult().orElseThrow();
            check("Alpha -> Beta.helper()", find(cu, "helper"));
            check("Alpha -> own twice()", find(cu, "twice"));
        } catch (Throwable t) {
            System.out.println("  SOLVER CONSTRUCTION FAILED: " + t);
            fail++;
        }

        // --- solver 2: dependency jars ---
        System.out.println("[JarTypeSolver] resolution into a dependency jar");
        if (jars.isEmpty()) {
            System.out.println("  (no jars supplied, skipped)");
        } else {
            try {
                CombinedTypeSolver ts = new CombinedTypeSolver();
                ParserConfiguration cfg = new ParserConfiguration()
                        .setLanguageLevel(LanguageLevel.JAVA_25)
                        .setSymbolResolver(new JavaSymbolSolver(ts));
                int added = 0;
                for (String j : jars) {
                    try { ts.add(new JarTypeSolver(j)); added++; }
                    catch (Throwable t) { System.out.println("  jar unreadable: " + j + " (" + t.getClass().getSimpleName() + ")"); }
                }
                ts.add(new JavaParserTypeSolver(corpus, cfg));
                System.out.println("  jars added: " + added);
                JavaParser p = new JavaParser(cfg);
                CompilationUnit cu = p.parse(corpus.resolve("UsesJar.java")).getResult().orElseThrow();
                check("JavaParser API from jar", find(cu, "getLanguageLevel"));
            } catch (Throwable t) {
                System.out.println("  SOLVER CONSTRUCTION FAILED: " + t);
                fail++;
            }
        }

        // --- solver 3: the JDK via reflection (closed-world suspect) ---
        System.out.println("[ReflectionTypeSolver] resolution into the JDK");
        try {
            CombinedTypeSolver ts = new CombinedTypeSolver();
            ParserConfiguration cfg = new ParserConfiguration()
                    .setLanguageLevel(LanguageLevel.JAVA_25)
                    .setSymbolResolver(new JavaSymbolSolver(ts));
            ts.add(new ReflectionTypeSolver(false));
            ts.add(new JavaParserTypeSolver(corpus, cfg));
            JavaParser p = new JavaParser(cfg);
            CompilationUnit cu = p.parse(corpus.resolve("UsesJdk.java")).getResult().orElseThrow();
            check("String.length()", find(cu, "length"));
            check("List.of(..).size()", find(cu, "size"));
            check("Map.Entry via stream", find(cu, "toList"));
        } catch (Throwable t) {
            System.out.println("  SOLVER CONSTRUCTION FAILED: " + t);
            fail++;
        }

        // --- solver 4: a JDK corner the tracing agent never saw ---
        // This file is created AFTER the agent run, so nothing here is pre-registered. On the JVM it resolves
        // by reflection; in a native image it can only work if the whole JDK surface was registered up front.
        System.out.println("[ReflectionTypeSolver] JDK types absent from the agent trace");
        Path unseen = corpus.resolve("UsesUnseenJdk.java");
        if (!Files.exists(unseen)) {
            System.out.println("  (UsesUnseenJdk.java absent, skipped)");
        } else {
            try {
                CombinedTypeSolver ts = new CombinedTypeSolver();
                ParserConfiguration cfg = new ParserConfiguration()
                        .setLanguageLevel(LanguageLevel.JAVA_25)
                        .setSymbolResolver(new JavaSymbolSolver(ts));
                ts.add(new ReflectionTypeSolver(false));
                ts.add(new JavaParserTypeSolver(corpus, cfg));
                CompilationUnit cu = new JavaParser(cfg).parse(unseen).getResult().orElseThrow();
                check("TreeMap.firstKey()", find(cu, "firstKey"));
                check("LocalDate.plusDays()", find(cu, "plusDays"));
                check("Pattern.matcher()", find(cu, "matcher"));
            } catch (Throwable t) {
                System.out.println("  SOLVER CONSTRUCTION FAILED: " + t);
                fail++;
            }
        }

        System.out.println("==== " + mode + ": " + pass + " passed, " + fail + " failed ====");
    }
}
