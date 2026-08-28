package sibarum.concordance.graph;

import java.util.List;

/**
 * A method in the receipt-graph: its parameter-constraint header, the variable standing for its result, and one
 * {@link Branch} per path through its body.
 *
 * <p>A node is a call site in the sense that matters here — the thing a {@link CallRef} points at. It appears
 * once per method, however many times the method is called, which is what makes a recursive edge a cycle rather
 * than an unfolding.
 *
 * <p>{@link #synthetic} marks a node the drafter invented rather than found. Java loops have no callee to point
 * back at, so a loop is lowered into a synthetic tail-recursive node whose back-edge is an ordinary
 * {@link CallRef}: the loop variable becomes the substituted argument, exactly as {@code n_1 := n_0 - 1} does for
 * a recursive call. That keeps "well-foundedness is a graph property" literally true for iteration, with no new
 * node kind and no invariant slot bolted on — but a consumer reporting back to a human needs to know the node has
 * no method of that name in the source.
 */
public record Node(
        MethodKey key,
        List<Param> params,
        Var resultVar,
        List<Branch> branches,
        boolean synthetic) {

    public Node {
        if (key == null) {
            throw new IllegalArgumentException("Node key must be non-null");
        }
        if (resultVar == null) {
            throw new IllegalArgumentException("Node resultVar must be non-null");
        }
        params = List.copyOf(params);
        branches = List.copyOf(branches);
        if (branches.isEmpty()) {
            throw new IllegalArgumentException(
                    "Node " + key.canonical() + " must have at least one branch");
        }
    }

    /** A node transcribed from a method that exists in the source. */
    public static Node of(MethodKey key, List<Param> params, Var resultVar, List<Branch> branches) {
        return new Node(key, params, resultVar, branches, false);
    }

    /** A node the drafter invented — today, the tail-recursive lowering of a loop. */
    public static Node synthetic(MethodKey key, List<Param> params, Var resultVar, List<Branch> branches) {
        return new Node(key, params, resultVar, branches, true);
    }

    /** The paths that produce a result, as opposed to the guard clauses that reject inputs. */
    public List<Branch> returningBranches() {
        return branches.stream().filter(b -> !b.isThrowing()).toList();
    }
}
