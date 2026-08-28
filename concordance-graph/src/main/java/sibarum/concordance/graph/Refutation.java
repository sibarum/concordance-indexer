package sibarum.concordance.graph;

import sibarum.cardinal.core.symbolic.BoundAnalysis;
import sibarum.cardinal.core.symbolic.SymExpr;
import sibarum.cardinal.core.types.Sort;

import java.util.ArrayList;
import java.util.List;

/**
 * Entailment over a path, and the refutation built on it.
 *
 * <p>This is the thin half of the reasoning, and the only half we own. The heavy half — linear-bound and sign
 * analysis over interval arithmetic — is Cardinal's public {@link BoundAnalysis}. What is left here is the part
 * that has to know about <em>our</em> graph: which domain a result belongs to, and how to gather a path.
 *
 * <p><b>Refutation, never proof.</b> A claim is rejected when the path facts entail its negation. A claim that
 * survives has not been validated; it has failed to be refuted, which is a weaker and more honest thing. The
 * asymmetry is what lets arbitrary issuers be admitted without the kernel understanding their reasoning.
 *
 * <p><b>The domain routing is the discreteness boundary.</b> A result sort named {@code Decimal} goes to dense
 * reasoning; anything else goes to the integer engine, which may cut {@code > c} to {@code >= c+1}. Getting this
 * wrong is silent, so {@link JavaSorts} is the only thing allowed to decide which Java types land where.
 */
public final class Refutation {

    private Refutation() {}

    /**
     * Whether {@code claim} is refuted on this path — that is, whether the path facts entail its negation.
     *
     * <p>A non-comparison claim cannot be negated by this kernel, so it is never refuted. That is deliberate: a
     * claim the kernel cannot analyse must not be rejected merely for being unfamiliar.
     */
    public static boolean refutes(Node node, Branch branch, SymExpr claim) {
        SymExpr negation = negate(claim);
        if (negation == null) {
            return false;
        }
        PathFacts facts = PathFacts.of(node, branch);
        return entails(node.resultVar().sort(), facts.hypotheses(), facts.substituteDefinition(negation));
    }

    /**
     * Whether {@code goal} follows from {@code hypotheses} in the domain of {@code resultSort}.
     *
     * <p>Conjunctions are flattened so a disjunctive hypothesis can surface, and a disjunction is case-split: the
     * goal holds under {@code {H, A ∨ B}} exactly when it holds under {@code {H, A}} and under {@code {H, B}}.
     * Sound because an {@code Or} only weakens the premise, so proving every disjunct proves the whole.
     */
    public static boolean entails(Sort resultSort, List<SymExpr> hypotheses, SymExpr goal) {
        List<SymExpr> flat = new ArrayList<>();
        for (SymExpr h : hypotheses) {
            flatten(h, flat);
        }
        for (int i = 0; i < flat.size(); i++) {
            if (flat.get(i) instanceof SymExpr.Or(SymExpr left, SymExpr right)) {
                return entails(resultSort, replaced(flat, i, left), goal)
                        && entails(resultSort, replaced(flat, i, right), goal);
            }
        }
        BoundAnalysis.Domain domain = JavaSorts.isDense(resultSort)
                ? BoundAnalysis.Domain.DECIMAL
                : BoundAnalysis.Domain.INT;
        return BoundAnalysis.discharge(domain, flat, goal);
    }

    /** Negates a comparison; null for anything else, which this kernel declines to judge. */
    public static SymExpr negate(SymExpr claim) {
        if (!(claim instanceof SymExpr.Cmp(SymExpr left, SymExpr.CmpOp op, SymExpr right))) {
            return null;
        }
        SymExpr.CmpOp flipped = switch (op) {
            case GE -> SymExpr.CmpOp.LT;
            case GT -> SymExpr.CmpOp.LE;
            case LE -> SymExpr.CmpOp.GT;
            case LT -> SymExpr.CmpOp.GE;
            case EQ -> SymExpr.CmpOp.NE;
            case NE -> SymExpr.CmpOp.EQ;
        };
        return SymExpr.cmp(left, flipped, right);
    }

    private static void flatten(SymExpr e, List<SymExpr> out) {
        if (e instanceof SymExpr.And(SymExpr left, SymExpr right)) {
            flatten(left, out);
            flatten(right, out);
        } else {
            out.add(e);
        }
    }

    private static List<SymExpr> replaced(List<SymExpr> source, int index, SymExpr with) {
        List<SymExpr> copy = new ArrayList<>(source);
        copy.set(index, with);
        return copy;
    }
}
