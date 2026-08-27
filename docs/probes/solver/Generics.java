import com.github.javaparser.*;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.*;
import java.util.*;

/** How wide is the generic-inference-through-lambda gap? Each case names the call it must resolve. */
public class Generics {

    static JavaParser parser() {
        CombinedTypeSolver ts = new CombinedTypeSolver();
        ParserConfiguration cfg = new ParserConfiguration()
                .setLanguageLevel(LanguageLevel.JAVA_25)
                .setSymbolResolver(new JavaSymbolSolver(ts));
        ts.add(new ReflectionTypeSolver(false));
        return new JavaParser(cfg);
    }

    static int pass = 0, fail = 0;

    static void probe(String label, String target, String body) {
        String src = "import java.util.*;\nimport java.util.stream.*;\nimport java.util.function.*;\n"
                + "class T {\n" + body + "\n}\n";
        ParseResult<CompilationUnit> r = parser().parse(src);
        if (!r.isSuccessful()) {
            System.out.printf("  PARSE-FAIL %-44s %s%n", label,
                    r.getProblems().get(0).getMessage().replaceAll("\\s+", " "));
            fail++;
            return;
        }
        List<MethodCallExpr> calls = r.getResult().orElseThrow().findAll(MethodCallExpr.class).stream()
                .filter(c -> c.getNameAsString().equals(target)).toList();
        if (calls.isEmpty()) { System.out.printf("  NO-TARGET  %-44s (%s)%n", label, target); fail++; return; }
        MethodCallExpr c = calls.get(calls.size() - 1);
        try {
            c.resolve();
            System.out.printf("  ok    %-44s %s%n", label, target);
            pass++;
        } catch (Throwable t) {
            String m = String.valueOf(t.getMessage()).replaceAll("\\s+", " ");
            System.out.printf("  FAIL  %-44s %s: %s%n", label, t.getClass().getSimpleName(),
                    m.length() > 90 ? m.substring(0, 90) + "..." : m);
            fail++;
        }
    }

    public static void main(String[] args) {
        System.out.println("=== map/collection methods taking a lambda ===");
        probe("computeIfAbsent + lambda, then call on result", "add",
                "Map<String, List<Integer>> m = new HashMap<>();\n"
                + "void f() { m.computeIfAbsent(\"k\", k -> new ArrayList<>()).add(1); }");
        probe("computeIfAbsent + lambda, resolve the call itself", "computeIfAbsent",
                "Map<String, List<Integer>> m = new HashMap<>();\n"
                + "void f() { m.computeIfAbsent(\"k\", k -> new ArrayList<>()); }");
        probe("computeIfAbsent + method ref", "computeIfAbsent",
                "Map<String, List<Integer>> m = new HashMap<>();\n"
                + "void f() { m.computeIfAbsent(\"k\", k -> List.of(1)); }");
        probe("merge + lambda", "merge",
                "Map<String, Integer> m = new HashMap<>();\n"
                + "void f() { m.merge(\"k\", 1, Integer::sum); }");
        probe("forEach + lambda", "forEach",
                "Map<String, Integer> m = new HashMap<>();\n"
                + "void f() { m.forEach((k, v) -> System.out.println(k)); }");
        probe("removeIf + lambda", "removeIf",
                "List<String> xs = new ArrayList<>();\n"
                + "void f() { xs.removeIf(s -> s.isEmpty()); }");

        System.out.println();
        System.out.println("=== stream pipelines ===");
        probe("map(lambda).toList()", "toList",
                "List<String> xs = new ArrayList<>();\n"
                + "void f() { xs.stream().map(s -> s.trim()).toList(); }");
        probe("map(methodref).collect(toList())", "collect",
                "List<String> xs = new ArrayList<>();\n"
                + "void f() { xs.stream().map(String::trim).collect(Collectors.toList()); }");
        probe("filter.map.sorted.findFirst", "findFirst",
                "List<String> xs = new ArrayList<>();\n"
                + "void f() { xs.stream().filter(s -> !s.isEmpty()).map(String::trim).sorted().findFirst(); }");
        probe("groupingBy", "collect",
                "List<String> xs = new ArrayList<>();\n"
                + "void f() { xs.stream().collect(Collectors.groupingBy(s -> s.length())); }");
        probe("flatMap", "flatMap",
                "List<List<String>> xs = new ArrayList<>();\n"
                + "void f() { xs.stream().flatMap(List::stream).toList(); }");
        probe("call a method ON a stream result", "length",
                "List<String> xs = new ArrayList<>();\n"
                + "void f() { xs.stream().map(s -> s.trim()).findFirst().get().length(); }");

        System.out.println();
        System.out.println("=== Optional / var / generic user methods ===");
        probe("Optional.map(lambda).orElse", "orElse",
                "Optional<String> o = Optional.empty();\n"
                + "void f() { o.map(s -> s.trim()).orElse(\"\"); }");
        probe("var from a generic call, then use", "trim",
                "List<String> xs = new ArrayList<>();\n"
                + "void f() { var first = xs.get(0); first.trim(); }");
        probe("own generic method with lambda param", "apply2",
                "<A, B> B apply2(A a, Function<A, B> fn) { return fn.apply(a); }\n"
                + "void f() { apply2(\"s\", x -> x.length()); }");
        probe("chained own generic method result", "intValue",
                "<A, B> B apply2(A a, Function<A, B> fn) { return fn.apply(a); }\n"
                + "void f() { apply2(\"s\", x -> x.length()).intValue(); }");
        probe("nested lambda", "add",
                "Map<String, List<Integer>> m = new HashMap<>();\n"
                + "void f() { List.of(\"a\").forEach(k -> m.computeIfAbsent(k, x -> new ArrayList<>()).add(1)); }");

        System.out.println();
        System.out.printf("=== %d resolved, %d failed ===%n", pass, fail);
    }
}
