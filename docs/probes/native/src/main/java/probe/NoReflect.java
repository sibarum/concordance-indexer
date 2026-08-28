package probe;

import com.github.javaparser.*;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import java.nio.file.Path;

/**
 * The metamodel reflection comes from the post-parse validators, not the parser. If that is true, parsing with
 * the validators off should work in a native image with no reachability metadata at all.
 *
 * Built deliberately WITHOUT reachability-metadata.json.
 */
public class NoReflect {

    static void attempt(String label, ParserConfiguration cfg, Path file) {
        try {
            ParseResult<CompilationUnit> r = new JavaParser(cfg).parse(file);
            if (!r.isSuccessful()) {
                System.out.printf("  %-46s parse unsuccessful: %s%n", label,
                        r.getProblems().get(0).getMessage().replaceAll("\\s+", " "));
                return;
            }
            CompilationUnit cu = r.getResult().orElseThrow();
            System.out.printf("  OK    %-40s nodes=%d, types=%s%n", label,
                    cu.findAll(Node.class).size(),
                    cu.getTypes().stream().map(t -> t.getNameAsString()).toList());
        } catch (Throwable t) {
            System.out.printf("  FAIL  %-40s %s: %s%n", label, t.getClass().getName(), t.getMessage());
        }
    }

    public static void main(String[] args) {
        String mode = System.getProperty("org.graalvm.nativeimage.imagecode") != null ? "NATIVE IMAGE" : "JVM";
        System.out.println("==== NoReflect on: " + mode + " (no reachability metadata) ====");
        Path file = Path.of(args[0]);

        attempt("JAVA_25 (validators on)", new ParserConfiguration()
                .setLanguageLevel(LanguageLevel.JAVA_25), file);

        attempt("RAW (validators off)", new ParserConfiguration()
                .setLanguageLevel(LanguageLevel.RAW), file);

        // The knob that actually matters if RAW is too blunt: keep the level, drop the post-processor.
        ParserConfiguration noPost = new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_25);
        noPost.getProcessors().clear();
        attempt("JAVA_25, processors cleared", noPost, file);
    }
}
