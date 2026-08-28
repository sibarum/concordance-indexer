import com.github.javaparser.*;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

/** What is actually lost by clearing ParserConfiguration.getProcessors() to avoid the validator reflection? */
public class ProcessorCost {

    static final String SRC = """
            package a.b;

            import java.util.List;

            /** Javadoc on the class. */
            public class A extends B {
                /// Markdown doc on the method.
                int twice(int n) {
                    // a line comment
                    return n * 2; /* trailing block */
                }

                A(int x) {
                    int y = x * 2;
                    super(y);
                }
            }
            """;

    static void report(String label, ParserConfiguration cfg) {
        System.out.println("-- " + label);
        ParseResult<CompilationUnit> r = new JavaParser(cfg).parse(SRC);
        System.out.println("   parsed ok      : " + r.isSuccessful());
        if (!r.isSuccessful()) {
            r.getProblems().stream().limit(2)
                    .forEach(p -> System.out.println("     problem: " + p.getMessage().replaceAll("\\s+", " ")));
            return;
        }
        CompilationUnit cu = r.getResult().orElseThrow();
        System.out.println("   nodes          : " + cu.findAll(com.github.javaparser.ast.Node.class).size());
        System.out.println("   all comments   : " + cu.getAllComments().size());
        MethodDeclaration m = cu.findFirst(MethodDeclaration.class).orElseThrow();
        System.out.println("   method comment : " + m.getComment()
                .map(c -> c.getClass().getSimpleName() + " attached").orElse("NONE"));
        System.out.println("   orphan comments: " + cu.getOrphanComments().size());
        try {
            LexicalPreservingPrinter.setup(cu);
            String printed = LexicalPreservingPrinter.print(cu);
            System.out.println("   LPP round-trip : " + (printed.equals(SRC) ? "byte-exact" : "DIFFERS"));
            // and an actual edit, which is the refactoring path
            cu.findFirst(MethodDeclaration.class).orElseThrow().setName("twiceRenamed");
            String edited = LexicalPreservingPrinter.print(cu);
            boolean ok = edited.contains("int twiceRenamed(int n)")
                    && edited.contains("/// Markdown doc on the method.")
                    && edited.contains("// a line comment");
            System.out.println("   rename + print : " + (ok ? "clean, comments preserved" : "BROKEN"));
        } catch (Throwable t) {
            System.out.println("   LPP            : threw " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    public static void main(String[] args) {
        report("JAVA_25, default processors", new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_25));

        ParserConfiguration cleared = new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_25);
        int n = cleared.getProcessors().size();
        cleared.getProcessors().clear();
        report("JAVA_25, " + n + " processors cleared", cleared);

        report("RAW", new ParserConfiguration().setLanguageLevel(LanguageLevel.RAW));

        System.out.println();
        System.out.println("=== does clearing processors also drop validation of bad code? ===");
        String bad = "class A { void f() { int x = 1; super(); } }\n";  // super() not first, illegal before Java 25
        for (var lvl : new LanguageLevel[]{ LanguageLevel.JAVA_21 }) {
            ParserConfiguration on = new ParserConfiguration().setLanguageLevel(lvl);
            ParserConfiguration off = new ParserConfiguration().setLanguageLevel(lvl);
            off.getProcessors().clear();
            System.out.printf("   %s validators on  -> %s%n", lvl,
                    new JavaParser(on).parse(bad).isSuccessful() ? "accepted" : "rejected");
            System.out.printf("   %s validators off -> %s%n", lvl,
                    new JavaParser(off).parse(bad).isSuccessful() ? "accepted" : "rejected");
        }
    }
}
