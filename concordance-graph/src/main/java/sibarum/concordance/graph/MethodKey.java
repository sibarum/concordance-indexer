package sibarum.concordance.graph;

import java.util.List;

/**
 * Identity of a method, and therefore of a {@link Node}.
 *
 * <p>Cardinal keys a node by function name and defers the question of which overload a call dispatches to.
 * Java does not allow that deferral: overloading is pervasive, the same simple name lives in unrelated types,
 * and the no-duplicate-edges rule only does its work — well-foundedness as a graph property — if a back-reference
 * lands on exactly one node. So identity here is the triple that Java itself uses to distinguish methods:
 * declaring type, name, and the erased parameter types.
 *
 * <p>Parameter types are stored as written-and-then-resolved strings rather than {@link
 * sibarum.cardinal.core.types.Sort}s. A key must be comparable and hashable long before refinement predicates are
 * known, and two overloads differing only in a refinement do not exist in Java.
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
