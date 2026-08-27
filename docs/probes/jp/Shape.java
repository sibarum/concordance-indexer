import com.github.javaparser.*;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.stmt.*;
import java.util.*;

public class Shape {
    static CompilationUnit parse(LanguageLevel l, String src) {
        ParseResult<CompilationUnit> r = new JavaParser(new ParserConfiguration().setLanguageLevel(l)).parse(src);
        if (!r.isSuccessful()) { System.out.println("   parse FAILED: " + r.getProblems().getFirst().getMessage().replace('\n',' ')); return null; }
        return r.getResult().orElse(null);
    }
    static void dump(String label, Node n, int depth) {
        System.out.printf("   %s%s : %s%n", "  ".repeat(depth), label, n.getClass().getSimpleName());
    }

    public static void main(String[] a) {
        LanguageLevel L25 = LanguageLevel.JAVA_25;

        System.out.println("[1] Flexible constructor body — statement order preserved?");
        CompilationUnit cu = parse(L25, """
            class A extends B {
                A(int x) {
                    if (x < 0) throw new IllegalArgumentException();
                    int y = x * 2;
                    super(y);
                    this.z = y;
                }
                int z;
            }
            """);
        if (cu != null) {
            ConstructorDeclaration ctor = cu.findFirst(ConstructorDeclaration.class).orElseThrow();
            NodeList<Statement> body = ctor.getBody().getStatements();
            for (int i = 0; i < body.size(); i++) {
                Statement s = body.get(i);
                String extra = s instanceof ExplicitConstructorInvocationStmt e
                        ? "  <== explicit ctor invocation, isThis=" + e.isThis() : "";
                System.out.printf("   stmt[%d] %-34s %s%s%n", i, s.getClass().getSimpleName(),
                        s.toString().replace('\n',' '), extra);
            }
            System.out.println("   -> super() at index " + body.stream()
                    .filter(s -> s instanceof ExplicitConstructorInvocationStmt).findFirst()
                    .map(body::indexOf).orElse(-1) + " (2 = prologue kept, 0 = prologue lost)");
        }

        System.out.println();
        System.out.println("[2] Compact source file — how is it modelled?");
        cu = parse(L25, "private int n = 3;\nint twice() { return n * 2; }\nvoid main() { IO.println(twice()); }\n");
        if (cu != null) {
            System.out.println("   types: " + cu.getTypes().stream().map(t -> t.getClass().getSimpleName()
                    + "(name=" + t.getNameAsString() + ")").toList());
            cu.getTypes().forEach(t -> t.getMembers().forEach(m ->
                    System.out.println("     member: " + m.getClass().getSimpleName() + " -> "
                            + m.toString().replace('\n',' '))));
        }

        System.out.println();
        System.out.println("[3] Module import declaration — dedicated node?");
        cu = parse(L25, "import module java.base;\nimport java.util.List;\nclass A {}\n");
        if (cu != null) cu.getImports().forEach(i -> System.out.println("   " + i.getClass().getSimpleName()
                + " name=" + i.getNameAsString() + " isAsterisk=" + i.isAsterisk() + " isStatic=" + i.isStatic()
                + " src='" + i.toString().trim() + "'"));

        System.out.println();
        System.out.println("[4] Markdown doc comment — comment type?");
        cu = parse(L25, "class A {\n    /// md *doc*\n    /// see [String]\n    void f() {}\n}\n");
        if (cu != null) cu.findFirst(MethodDeclaration.class).ifPresent(m ->
                System.out.println("   comment: " + m.getComment().map(c -> c.getClass().getSimpleName()
                        + " isJavadoc=" + c.isJavadocComment() + " content='"
                        + c.getContent().strip().replace('\n','|') + "'").orElse("NONE")));

        System.out.println();
        System.out.println("[5] Is the language level actually enforced? (same sources at JAVA_21)");
        String[][ ] probes = {
            {"module import",      "import module java.base;\nclass A {}\n"},
            {"compact source",     "void main() {}\n"},
            {"flexible ctor",      "class A extends B { A() { int y = 1; super(y); } }\n"},
            {"markdown comment",   "class A {\n    /// md\n    void f() {}\n}\n"},
            {"primitive pattern",  "class A { int f(Object o) { return switch (o) { case int i -> i; default -> 0; }; } }\n"},
        };
        for (String[] pr : probes) {
            ParseResult<CompilationUnit> r21 = new JavaParser(new ParserConfiguration()
                    .setLanguageLevel(LanguageLevel.JAVA_21)).parse(pr[1]);
            System.out.printf("   JAVA_21 %-18s -> %s%n", pr[0],
                    r21.isSuccessful() ? "ACCEPTED (no gating)"
                    : "rejected: " + r21.getProblems().getFirst().getMessage().replace('\n',' '));
        }
    }
}
