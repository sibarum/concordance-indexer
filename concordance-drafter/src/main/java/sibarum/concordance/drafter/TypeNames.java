package sibarum.concordance.drafter;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.Type;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Type and method names, as written.
 *
 * <p>These are <em>syntactic</em> names, not resolved ones. Until Concordance has its own resolver, a parameter
 * declared {@code List<String>} yields {@code "List"} and not {@code java.util.List}, and a
 * {@code MethodKey} built from it distinguishes overloads by written type rather than by erasure. That is enough
 * for a back-reference to land on one node within a project drafted as a whole, and it is not enough to match a
 * call against a dependency's method — which is exactly the work the resolver will do.
 *
 * <p>The one thing this must not do is guess. A name it cannot determine is returned as written, so an
 * unresolvable id shows up in the graph as an odd-looking id rather than as a wrong edge.
 */
final class TypeNames {

    private TypeNames() {}

    /** The dotted name of the type a member is declared in, qualified by package and enclosing types. */
    static String declaringType(TypeDeclaration<?> type) {
        Deque<String> parts = new ArrayDeque<>();
        parts.addFirst(type.getNameAsString());
        var node = type.getParentNode();
        while (node.isPresent()) {
            Object n = node.get();
            if (n instanceof TypeDeclaration<?> outer) {
                parts.addFirst(outer.getNameAsString());
                node = outer.getParentNode();
            } else if (n instanceof CompilationUnit cu) {
                cu.getPackageDeclaration().ifPresent(p -> parts.addFirst(p.getNameAsString()));
                break;
            } else {
                node = ((com.github.javaparser.ast.Node) n).getParentNode();
            }
        }
        return String.join(".", parts);
    }

    /**
     * A type as written, with generic arguments and annotations dropped.
     *
     * <p>Erasure is the right shape for a method key, and dropping type arguments is the syntactic approximation
     * of it: {@code List<String>} and {@code List<Integer>} cannot be overloads of one another anyway, because
     * Java erases them to the same descriptor.
     */
    static String of(Type type) {
        String written = type.asString();
        int generic = written.indexOf('<');
        String base = generic < 0 ? written : written.substring(0, generic) + written.substring(closing(written, generic) + 1);
        return base.trim();
    }

    private static int closing(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            if (s.charAt(i) == '<') depth++;
            else if (s.charAt(i) == '>' && --depth == 0) return i;
        }
        return s.length() - 1;
    }

    /** Whether {@code type} is the kind of declaration whose methods become nodes. */
    static boolean draftable(TypeDeclaration<?> type) {
        return !(type instanceof ClassOrInterfaceDeclaration c && c.isInterface() && c.getMethods().stream()
                .noneMatch(m -> m.getBody().isPresent()));
    }
}
