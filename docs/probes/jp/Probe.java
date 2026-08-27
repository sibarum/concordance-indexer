import com.github.javaparser.*;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import java.util.*;

public class Probe {

    record Case(String name, String src) {}

    static final List<Case> CASES = List.of(
        new Case("JEP 511 module import declaration", """
            import module java.base;
            public class A { void f() { List<String> xs = new ArrayList<>(); } }
            """),
        new Case("JEP 512 compact source file + instance main", """
            void main() {
                IO.println("hi");
            }
            """),
        new Case("JEP 512 compact file with field + helper", """
            private int n = 3;
            int twice() { return n * 2; }
            void main() { IO.println(twice()); }
            """),
        new Case("JEP 513 flexible constructor body (stmt before super)", """
            public class A extends B {
                A(int x) {
                    if (x < 0) throw new IllegalArgumentException();
                    int y = x * 2;
                    super(y);
                    this.z = y;
                }
                int z;
            }
            """),
        new Case("JEP 513 flexible ctor body (stmt before this(..))", """
            public class A {
                A(int x) { var v = x + 1; this(v, 0); }
                A(int a, int b) {}
            }
            """),
        new Case("JEP 467 markdown doc comment", """
            public class A {
                /// A *markdown* doc comment.
                /// - one
                /// - two
                ///
                /// See [String].
                public void f() {}
            }
            """),
        new Case("switch expression with yield (issue 4949)", """
            public class A {
                int f(int k) {
                    return switch (k) {
                        case 1 -> 1;
                        default -> { yield 0; }
                    };
                }
            }
            """),
        new Case("record pattern + unnamed pattern _", """
            public class A {
                record P(int x, int y) {}
                int f(Object o) {
                    return switch (o) {
                        case P(int x, _) -> x;
                        case null, default -> 0;
                    };
                }
            }
            """),
        new Case("sealed + permits + pattern switch", """
            public sealed interface S permits X, Y {}
            """),
        new Case("PREVIEW JEP 507 primitive type in pattern", """
            public class A {
                String f(Object o) {
                    return switch (o) {
                        case int i when i > 0 -> "pos";
                        case int i -> "nonpos";
                        default -> "other";
                    };
                }
            }
            """),
        new Case("text block ending in escaped backslash", """
            public class A { String s = \"""
                a\\\\\"""; }
            """),
        new Case("module-info with multiple exports", """
            module m {
                exports a.b;
                exports c.d;
                requires java.base;
            }
            """)
    );

    public static void main(String[] args) {
        for (LanguageLevel level : new LanguageLevel[]{ LanguageLevel.JAVA_25, LanguageLevel.JAVA_26 }) {
            System.out.println("================ LanguageLevel." + level + " ================");
            JavaParser p = new JavaParser(new ParserConfiguration().setLanguageLevel(level));
            int ok = 0, fail = 0;
            for (Case c : CASES) {
                ParseResult<CompilationUnit> r;
                try {
                    r = p.parse(c.src());
                } catch (Throwable t) {
                    System.out.printf("  THREW  %-52s %s%n", c.name(), t);
                    fail++;
                    continue;
                }
                if (r.isSuccessful() && r.getResult().isPresent()) {
                    CompilationUnit cu = r.getResult().get();
                    String rt;
                    try {
                        LexicalPreservingPrinter.setup(cu);
                        String printed = LexicalPreservingPrinter.print(cu);
                        rt = printed.equals(c.src()) ? "LPP exact" : "LPP DIFFERS";
                    } catch (Throwable t) {
                        rt = "LPP threw: " + t.getClass().getSimpleName();
                    }
                    System.out.printf("  OK     %-52s (%s)%n", c.name(), rt);
                    ok++;
                } else {
                    System.out.printf("  FAIL   %-52s%n", c.name());
                    r.getProblems().stream().limit(2)
                        .forEach(pr -> System.out.println("           -> " + pr.getVerboseMessage().replace('\n', ' ')));
                    fail++;
                }
            }
            System.out.println("  ---- " + ok + " parsed, " + fail + " failed");
        }
        System.out.println();
        System.out.println("javaparser CURRENT        = " + LanguageLevel.CURRENT);
        System.out.println("javaparser BLEEDING_EDGE  = " + LanguageLevel.BLEEDING_EDGE);
        System.out.println("any *_PREVIEW above 17?   = " + Arrays.stream(LanguageLevel.values())
                .map(Enum::name).filter(n -> n.endsWith("_PREVIEW")).toList());
    }
}
