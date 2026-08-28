package sibarum.concordance.graph;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The receipt-graph: ground truth, immutable, the drafter's only output.
 *
 * <p>An ordered list of {@link Node}s keyed by {@link MethodKey}, plus the index that makes a {@link CallRef} a
 * resolvable edge. Order is source declaration order across the project, preserved, so two drafts of unchanged
 * source are comparable element by element — which is what a skeleton-match check needs.
 *
 * <p>Nothing here concludes anything. Facts inside the graph are transcriptions; conclusions about the graph are
 * closing receipts, produced by issuers, referencing in and never extending it.
 *
 * <p>The no-duplicate-edges rule is enforced at construction: keys are unique, so a call to an already-present
 * method is necessarily a back-reference rather than a second copy.
 */
public record ReceiptGraph(List<Node> roots) {

    public ReceiptGraph {
        if (roots == null) {
            throw new IllegalArgumentException("ReceiptGraph roots must be non-null");
        }
        roots = List.copyOf(roots);
        Map<MethodKey, Integer> seen = new LinkedHashMap<>();
        for (int i = 0; i < roots.size(); i++) {
            MethodKey key = roots.get(i).key();
            Integer prior = seen.putIfAbsent(key, i);
            if (prior != null) {
                throw new IllegalArgumentException(
                        "duplicate node for " + key.canonical() + " at indices " + prior + " and " + i
                                + "; a repeated method must be one node referenced twice, not two nodes");
            }
        }
    }

    /** The node a {@link CallRef} points at, or empty when the target is outside the drafted set. */
    public Optional<Node> nodeFor(MethodKey key) {
        return roots.stream().filter(n -> n.key().equals(key)).findFirst();
    }

    /**
     * Whether this call is a back-reference — a call into the drafted set, and therefore an edge that closes a
     * cycle when it points at an ancestor. A call to something we did not draft (a JDK method, a dependency) is
     * not an edge at all; it is an opaque value with whatever refinement its sort carries.
     */
    public boolean isBackReference(CallRef call) {
        return nodeFor(call.target()).isPresent();
    }

    /** Every node declared on {@code declaringType}, in declaration order. */
    public List<Node> nodesIn(String declaringType) {
        return roots.stream().filter(n -> n.key().declaringType().equals(declaringType)).toList();
    }

    /** Every overload of {@code methodName} on {@code declaringType}, in declaration order. */
    public List<Node> nodesNamed(String declaringType, String methodName) {
        return roots.stream()
                .filter(n -> n.key().declaringType().equals(declaringType))
                .filter(n -> n.key().methodName().equals(methodName))
                .toList();
    }
}
