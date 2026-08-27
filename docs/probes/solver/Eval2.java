import com.github.javaparser.*;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.*;
import com.github.javaparser.resolution.Context;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory;
import com.github.javaparser.symbolsolver.resolution.typesolvers.*;
import java.nio.file.*;
import java.util.*;

/**
 * Corrected symbol-solver evaluation.
 *
 * Three fixes over the first pass:
 *   1. JavaParserTypeSolver is handed the resolver-bearing ParserConfiguration, so types reached through
 *      source (rather than through a jar) still carry a symbol resolver.
 *   2. Package qualifiers (the scope half of a qualified type name) are not counted as unresolvable types.
 *   3. A name in "value position" that is really a type name counts as resolved-as-type rather than failed,
 *      which is what any real indexer would do.
 *
 * Failures are split into SOLVER DEFECTS (the solver broke) and UNRESOLVED (the solver looked and did not find).
 */
public class Eval2 {

    static final class Tally {
        final String name;
        int attempted, asValue, asType;
        final Map<String, Integer> defects = new TreeMap<>();
        final Map<String, Integer> unresolved = new TreeMap<>();
        final Map<String, String> example = new HashMap<>();

        Tally(String name) { this.name = name; }

        int solved() { return asValue + asType; }
        double rate() { return attempted == 0 ? 1.0 : (double) solved() / attempted; }
    }

    static boolean isDefect(Throwable t) {
        String n = t.getClass().getSimpleName();
        return n.equals("UnsupportedOperationException") || n.equals("IllegalStateException")
                || n.equals("StackOverflowError") || n.equals("ClassCastException")
                || n.equals("NullPointerException") || n.equals("IndexOutOfBoundsException")
                || n.equals("MethodAmbiguityException") || n.equals("RuntimeException");
    }

    static String oneLine(String s) {
        if (s == null) return "no message";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }

    static String snippet(Node n) {
        String s = n.toString().replaceAll("\\s+", " ").trim();
        String file = n.findCompilationUnit().flatMap(CompilationUnit::getStorage)
                .map(CompilationUnit.Storage::getFileName).orElse("?");
        String line = n.getBegin().map(p -> String.valueOf(p.line)).orElse("?");
        return (s.length() > 60 ? s.substring(0, 60) + "..." : s) + "  (" + file + ":" + line + ")";
    }

    static void record(Tally t, Throwable err, Node n) {
        Map<String, Integer> bucket = isDefect(err) ? t.defects : t.unresolved;
        String key = err.getClass().getSimpleName();
        bucket.merge(key, 1, Integer::sum);
        t.example.putIfAbsent(key, snippet(n) + "   [" + oneLine(err.getMessage()) + "]");
    }

    /** Attempt value resolution; on failure, fall back to type resolution through the node context. */
    static void attemptValueThenType(Tally t, Node n, String nameText, TypeSolver solver, Resolver<Node> valueResolve) {
        t.attempted++;
        try {
            valueResolve.apply(n);
            t.asValue++;
            return;
        } catch (Throwable valueErr) {
            if (isDefect(valueErr)) { record(t, valueErr, n); return; }
            try {
                Context ctx = JavaParserFactory.getContext(n, solver);
                // Try the qualified text first (Outer.Inner), then the simple name.
                if (ctx.solveType(n.toString()).isSolved() || ctx.solveType(nameText).isSolved()) {
                    t.asType++;
                    return;
                }
            } catch (Throwable ignored) {
                // fall through to reporting the original value-resolution failure
            }
            record(t, valueErr, n);
        }
    }

    static <N extends Node> void attempt(Tally t, Iterable<N> nodes, Resolver<N> r) {
        for (N n : nodes) {
            t.attempted++;
            try { r.apply(n); t.asValue++; }
            catch (Throwable err) { record(t, err, n); }
        }
    }

    interface Resolver<N> { Object apply(N n) throws Throwable; }

    /** A qualified type name nests its package as scope; those inner nodes are packages, not types. */
    static boolean isPackageQualifier(ClassOrInterfaceType t) {
        return t.getParentNode().filter(p -> p instanceof ClassOrInterfaceType c
                && c.getScope().map(s -> s == t).orElse(false)).isPresent();
    }

    public static void main(String[] args) throws Exception {
        String label = args[0];
        Path srcRoot = Path.of(args[1]);
        Path cpFile = args.length > 2 && !args[2].equals("-") ? Path.of(args[2]) : null;
        List<Path> extraSrcRoots = new ArrayList<>();
        for (int i = 3; i < args.length; i++) extraSrcRoots.add(Path.of(args[i]));

        System.out.println("############ " + label + " ############");

        long t0 = System.nanoTime();
        CombinedTypeSolver solver = new CombinedTypeSolver();
        // Deferred wiring: the config names the resolver, the resolver names this (still-empty) combined solver.
        ParserConfiguration cfg = new ParserConfiguration()
                .setLanguageLevel(LanguageLevel.JAVA_25)
                .setSymbolResolver(new JavaSymbolSolver(solver));

        solver.add(new ReflectionTypeSolver(false));
        solver.add(new JavaParserTypeSolver(srcRoot, cfg));
        int extra = 0;
        for (Path p : extraSrcRoots) {
            if (Files.isDirectory(p)) { solver.add(new JavaParserTypeSolver(p, cfg)); extra++; }
        }
        int jars = 0, jarFail = 0;
        if (cpFile != null && Files.exists(cpFile)) {
            for (String entry : Files.readString(cpFile).trim().split(";")) {
                if (entry.isBlank() || !entry.endsWith(".jar")) continue;
                try { solver.add(new JarTypeSolver(entry)); jars++; }
                catch (Throwable t) { jarFail++; }
            }
        }
        System.out.printf("type solver: %d jars (%d unreadable), %d extra source roots, built in %d ms%n",
                jars, jarFail, extra, (System.nanoTime() - t0) / 1_000_000);

        JavaParser parser = new JavaParser(cfg);
        List<Path> files;
        try (var s = Files.walk(srcRoot)) {
            files = s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }

        long tParse0 = System.nanoTime();
        List<CompilationUnit> units = new ArrayList<>();
        int parseFail = 0;
        for (Path f : files) {
            ParseResult<CompilationUnit> r = parser.parse(f);
            if (r.isSuccessful() && r.getResult().isPresent()) units.add(r.getResult().get());
            else {
                parseFail++;
                System.out.println("  PARSE FAIL " + srcRoot.relativize(f) + " : "
                        + oneLine(r.getProblems().isEmpty() ? null : r.getProblems().get(0).getMessage()));
            }
        }
        long tParse = System.nanoTime() - tParse0;
        System.out.printf("parsed %d/%d files in %d ms, %d parse failures%n",
                units.size(), files.size(), tParse / 1_000_000, parseFail);

        Tally calls = new Tally("method calls        MethodCallExpr");
        Tally ctors = new Tally("constructor calls   ObjectCreationExpr");
        Tally mrefs = new Tally("method refs         MethodReferenceExpr");
        Tally fields = new Tally("field accesses      FieldAccessExpr");
        Tally names = new Tally("simple names        NameExpr");
        Tally types = new Tally("type references     ClassOrInterfaceType");
        Tally callTy = new Tally("call return types   calculateResolvedType");
        List<Tally> all = List.of(calls, ctors, mrefs, fields, names, types, callTy);

        long tRes0 = System.nanoTime();
        for (CompilationUnit cu : units) {
            attempt(calls, cu.findAll(MethodCallExpr.class), MethodCallExpr::resolve);
            attempt(ctors, cu.findAll(ObjectCreationExpr.class), ObjectCreationExpr::resolve);
            attempt(mrefs, cu.findAll(MethodReferenceExpr.class), MethodReferenceExpr::resolve);
            attempt(callTy, cu.findAll(MethodCallExpr.class), MethodCallExpr::calculateResolvedType);

            for (FieldAccessExpr fa : cu.findAll(FieldAccessExpr.class))
                attemptValueThenType(fields, fa, fa.getNameAsString(), solver, n -> ((FieldAccessExpr) n).resolve());
            for (NameExpr ne : cu.findAll(NameExpr.class))
                attemptValueThenType(names, ne, ne.getNameAsString(), solver, n -> ((NameExpr) n).resolve());
            for (ClassOrInterfaceType ct : cu.findAll(ClassOrInterfaceType.class)) {
                if (isPackageQualifier(ct)) continue;
                types.attempted++;
                try { ct.resolve(); types.asValue++; }
                catch (Throwable err) { record(types, err, ct); }
            }
        }
        long tRes = System.nanoTime() - tRes0;

        System.out.println();
        System.out.printf("%-38s %7s %7s %7s %7s %7s%n", "category", "tried", "value", "type", "solved", "rate");
        int totA = 0, totS = 0, totD = 0;
        for (Tally t : all) {
            System.out.printf("%-38s %7d %7d %7d %7d %6.1f%%%n",
                    t.name, t.attempted, t.asValue, t.asType, t.solved(), t.rate() * 100);
            totA += t.attempted;
            totS += t.solved();
            totD += t.defects.values().stream().mapToInt(Integer::intValue).sum();
        }
        System.out.printf("%-38s %7d %7s %7s %7d %6.1f%%%n", "TOTAL", totA, "", "", totS, 100.0 * totS / Math.max(1, totA));
        System.out.printf("solver defects (crashes, not misses): %d  (%.2f%% of attempts)%n",
                totD, 100.0 * totD / Math.max(1, totA));
        System.out.printf("resolution wall time: %d ms (%.0f resolutions/s)%n",
                tRes / 1_000_000, totA / Math.max(1e-9, tRes / 1e9));

        System.out.println();
        System.out.println("---- SOLVER DEFECTS (these are bugs you would have to work around) ----");
        boolean anyDefect = false;
        for (Tally t : all) {
            if (t.defects.isEmpty()) continue;
            anyDefect = true;
            System.out.println("  " + t.name);
            t.defects.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> {
                        System.out.printf("    %-28s %5d%n", e.getKey(), e.getValue());
                        System.out.println("        e.g. " + t.example.get(e.getKey()));
                    });
        }
        if (!anyDefect) System.out.println("  none");

        System.out.println();
        System.out.println("---- UNRESOLVED (looked, did not find) ----");
        for (Tally t : all) {
            if (t.unresolved.isEmpty()) continue;
            System.out.println("  " + t.name);
            t.unresolved.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> {
                        System.out.printf("    %-28s %5d%n", e.getKey(), e.getValue());
                        System.out.println("        e.g. " + t.example.get(e.getKey()));
                    });
        }
        System.out.println();
    }
}
