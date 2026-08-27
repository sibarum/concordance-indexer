import com.github.javaparser.*;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.*;
import java.util.*;

/** Can the symbol solver resolve *through* Java 25 constructs, not merely parse them? */
public class Java25Res {

    static JavaParser parser() {
        CombinedTypeSolver ts = new CombinedTypeSolver();
        ParserConfiguration cfg = new ParserConfiguration()
                .setLanguageLevel(LanguageLevel.JAVA_25)
                .setSymbolResolver(new JavaSymbolSolver(ts));
        ts.add(new ReflectionTypeSolver(false));
        return new JavaParser(cfg);
    }

    static void probe(String label, String src, String targetCallName) {
        System.out.println("-- " + label);
        ParseResult<CompilationUnit> r = parser().parse(src);
        if (!r.isSuccessful()) {
            System.out.println("     PARSE FAILED: " + r.getProblems().get(0).getMessage().replaceAll("\\s+", " "));
            return;
        }
        CompilationUnit cu = r.getResult().orElseThrow();
        List<MethodCallExpr> targets = cu.findAll(MethodCallExpr.class).stream()
                .filter(c -> c.getNameAsString().equals(targetCallName)).toList();
        if (targets.isEmpty()) { System.out.println("     no call named " + targetCallName); return; }
        for (MethodCallExpr c : targets) {
            try {
                var d = c.resolve();
                System.out.printf("     RESOLVED  %-26s -> %s%n", c, d.getQualifiedSignature());
            } catch (Throwable t) {
                System.out.printf("     FAILED    %-26s -> %s: %s%n", c, t.getClass().getSimpleName(),
                        String.valueOf(t.getMessage()).replaceAll("\\s+", " ").substring(0,
                                Math.min(110, String.valueOf(t.getMessage()).replaceAll("\\s+", " ").length())));
            }
        }
    }

    public static void main(String[] args) {
        System.out.println("=== JEP 511: resolution through a module import ===");
        probe("import module java.base; then List.of(..)", """
                import module java.base;
                class A {
                    void f() {
                        var xs = List.of("a");
                        xs.size();
                    }
                }
                """, "size");
        probe("control: explicit import java.util.List", """
                import java.util.List;
                class A {
                    void f() {
                        var xs = List.of("a");
                        xs.size();
                    }
                }
                """, "size");

        System.out.println();
        System.out.println("=== JEP 512: resolution inside a compact source file ===");
        probe("call a sibling method in a compact file", """
                int twice(int n) { return n * 2; }
                void main() {
                    twice(3);
                }
                """, "twice");
        probe("control: same code in an explicit class", """
                class A {
                    int twice(int n) { return n * 2; }
                    void main() { twice(3); }
                }
                """, "twice");

        System.out.println();
        System.out.println("=== JEP 513: resolution inside a flexible constructor prologue ===");
        probe("method call before super(..)", """
                class B { B(int x) {} }
                class A extends B {
                    static int check(int x) { return x; }
                    A(int x) {
                        int y = check(x);
                        super(y);
                    }
                }
                """, "check");

        System.out.println();
        System.out.println("=== record accessors + pattern variables ===");
        probe("record accessor call", """
                class A {
                    record P(int x, int y) {}
                    void f(P p) { p.x(); }
                }
                """, "x");
        probe("method on a record-pattern binding", """
                class A {
                    record P(String s, int y) {}
                    int f(Object o) {
                        return switch (o) {
                            case P(String s, _) -> s.length();
                            default -> 0;
                        };
                    }
                }
                """, "length");

        System.out.println();
        System.out.println("=== markdown javadoc does not break member resolution ===");
        probe("markdown-documented method called", """
                class A {
                    /// Doubles [n].
                    int twice(int n) { return n * 2; }
                    void g() { twice(2); }
                }
                """, "twice");
    }
}
