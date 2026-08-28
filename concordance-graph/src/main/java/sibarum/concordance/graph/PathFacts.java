package sibarum.concordance.graph;

import sibarum.cardinal.core.symbolic.Substitute;
import sibarum.cardinal.core.symbolic.SymExpr;
import sibarum.cardinal.core.types.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Everything that holds along one {@link Branch}, gathered so a claim about that path can be checked.
 *
 * <p>Cardinal has the same idea, package-private to its receipts module; this is ours over our graph. The
 * gathering is mechanical and it is the whole reason the cycle earns its keep:
 *
 * <ul>
 *   <li><b>Parameter refinements</b> hold throughout the body. A method may assume them, because the paths that
 *       would violate them threw before getting here.
 *   <li><b>The branch guard</b>, when the path has one.
 *   <li><b>Each call's result refinement</b>, bound to that call's result variable. For a back-reference this
 *       <em>is</em> the inductive hypothesis — {@code r_1 >= 1} appears without anyone deciding to assume it,
 *       because the edge pointed at a node whose result carries that refinement.
 *   <li><b>Non-defining receipts</b>, as plain facts.
 * </ul>
 *
 * <p>The defining receipt — the {@code r_0 = rhs} body equation — is held apart rather than added as a
 * hypothesis, because a goal is normally stated about the result variable and has to be rewritten in terms of
 * what actually produced it before any bound can be computed. {@link #substituteDefinition(SymExpr)} does that.
 */
public final class PathFacts {

    private final String resultVarName;
    private final SymExpr definitionRhs;   // null when the path states no r = rhs equation
    private final List<SymExpr> hypotheses;

    private PathFacts(String resultVarName, SymExpr definitionRhs, List<SymExpr> hypotheses) {
        this.resultVarName = resultVarName;
        this.definitionRhs = definitionRhs;
        this.hypotheses = List.copyOf(hypotheses);
    }

    /** Gathers the facts holding on {@code branch} of {@code node}. */
    public static PathFacts of(Node node, Branch branch) {
        String resultVarName = node.resultVar().name();
        SymExpr definitionRhs = null;
        List<SymExpr> hyps = new ArrayList<>();

        for (Param p : node.params()) {
            if (p.sort().isRefined()) {
                hyps.add(Substitute.applySelf(p.sort().predicate(), SymExpr.var(p.name())));
            }
        }

        branch.guard().ifPresent(hyps::add);

        for (CallRef call : branch.calls()) {
            Sort resultSort = call.resultVar().sort();
            if (resultSort.isRefined()) {
                hyps.add(Substitute.applySelf(resultSort.predicate(), SymExpr.var(call.resultVar().name())));
            }
        }

        for (InitialReceipt r : branch.initialReceipts()) {
            if (isDefinitionOf(r.claim(), resultVarName)) {
                definitionRhs = ((SymExpr.Cmp) r.claim()).right();
            } else {
                hyps.add(r.claim());
            }
        }

        return new PathFacts(resultVarName, definitionRhs, hyps);
    }

    /** The facts that hold on this path: parameter refinements, guard, inductive hypotheses, plain receipts. */
    public List<SymExpr> hypotheses() {
        return hypotheses;
    }

    /** Whether this path states what the result is. A throwing path does not. */
    public boolean hasDefinition() {
        return definitionRhs != null;
    }

    /**
     * Rewrites {@code goal} in terms of what produced the result: {@code r_0 >= 0} becomes {@code n_0 * r_1 >= 0}.
     * Returns {@code goal} untouched when the path defines no result.
     */
    public SymExpr substituteDefinition(SymExpr goal) {
        return definitionRhs != null
                ? Substitute.apply(goal, Map.of(resultVarName, definitionRhs))
                : goal;
    }

    private static boolean isDefinitionOf(SymExpr claim, String resultVarName) {
        return claim instanceof SymExpr.Cmp(SymExpr left, SymExpr.CmpOp op, SymExpr ignored)
                && op == SymExpr.CmpOp.EQ
                && left instanceof SymExpr.Var v
                && v.name().equals(resultVarName);
    }
}
