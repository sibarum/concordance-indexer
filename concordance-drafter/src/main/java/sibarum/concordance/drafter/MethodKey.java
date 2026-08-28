package sibarum.concordance.drafter;

import java.util.List;

/**
 * Identity of a Java method, and the {@link sibarum.cardinal.receipts.Node#id()} it is registered under.
 *
 * <p>Cardinal's node id is an opaque string, so each drafter can be exactly as precise as its language allows.
 * Drafting from Pontif IR the id is a function name and overloads share it — the long-standing deferral. Java
 * cannot defer it: overloading is pervasive, the same simple name recurs across unrelated types, and the
 * no-duplicate-edges rule only does its work — well-foundedness as a property of the graph — when a
 * back-reference lands on exactly one node. So a Java id is the triple Java itself distinguishes methods by:
 * declaring type, name, and erased parameter types, rendered by {@link #canonical()}.
 *
 * <p>Parameter types are strings rather than {@link sibarum.cardinal.core.types.Sort}s. A key must be comparable
 * and hashable long before refinement predicates are known, and two overloads differing only in a refinement do
 * not exist in Java.
 */
public record MethodKey(String declaringType, String methodName, List<String> paramTypes) {

    public MethodKey {
        if (declaringType == null || declaringType.isEmpty()) {
            throw new IllegalArgumentException("MethodKey declaringType must be non-empty");
        }
        if (methodName == null || methodName.isEmpty()) {
            throw new IllegalArgumentException("MethodKey methodName must be non-empty");
        }
        paramTypes = List.copyOf(paramTypes);
    }

    /** Convenience for the common case of naming a key in a test or a diagnostic. */
    public static MethodKey of(String declaringType, String methodName, String... paramTypes) {
        return new MethodKey(declaringType, methodName, List.of(paramTypes));
    }

    /**
     * A stable one-line form, used for printing and for the graph's index.
     *
     * <p>{@code sibarum.demo.Math#factorial(int)}. The {@code #} separator is deliberate: it cannot occur in a
     * Java identifier or type name, so the form round-trips unambiguously.
     */
    public String canonical() {
        return declaringType + "#" + methodName + "(" + String.join(",", paramTypes) + ")";
    }

    @Override
    public String toString() {
        return canonical();
    }
}
