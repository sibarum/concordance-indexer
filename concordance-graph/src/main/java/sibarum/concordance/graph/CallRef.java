package sibarum.concordance.graph;

import sibarum.cardinal.core.symbolic.SymExpr;

import java.util.List;

/**
 * A sub-call on a {@link Branch}, pointing at a {@link Node} by {@link MethodKey} rather than expanding it.
 *
 * <p>When the target key matches a node already in the graph the reference <em>is</em> the back-edge, and that is
 * the mechanism the whole design rests on. Two consequences, both free:
 *
 * <ul>
 *   <li>Recursion cannot unfold infinitely, so well-foundedness is a property of the graph rather than a separate
 *       proof rule.
 *   <li>The edge carries the target's postcondition along as an inductive hypothesis. There is no step that says
 *       "assume the result of the recursive call satisfies its refinement" — the cycle already said it.
 * </ul>
 *
 * <p>{@code argBindings} are the substituted arguments, positionally matched to the target's params: the
 * {@code n_1 := n_0 - 1} of a recursive descent. {@code resultVar} names what the call returns on this path, so a
 * body equation can mention it.
 */
public record CallRef(MethodKey target, List<SymExpr> argBindings, Var resultVar) {

    public CallRef {
        if (target == null) {
            throw new IllegalArgumentException("CallRef target must be non-null");
        }
        if (resultVar == null) {
            throw new IllegalArgumentException("CallRef resultVar must be non-null");
        }
        argBindings = List.copyOf(argBindings);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(target.methodName()).append('(');
        for (int i = 0; i < argBindings.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(argBindings.get(i));
        }
        return sb.append(") : ").append(resultVar.name()).toString();
    }
}
