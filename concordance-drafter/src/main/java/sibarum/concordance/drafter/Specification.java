package sibarum.concordance.drafter;

import sibarum.cardinal.core.symbolic.SymExpr;
import sibarum.cardinal.core.types.Sort;
import sibarum.cardinal.receipts.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Supplies the refinements Java does not declare.
 *
 * <p>A drafted Java graph is structurally complete and proves nothing, because every obligation in Cardinal comes
 * from a refinement and a Java method declares {@code int} rather than {@code [Int:@>=1]}. This is the seam where
 * that arrives from somewhere else — an annotation, a specification file alongside the source, or an issuer's
 * conjecture — without the drafter having invented it.
 *
 * <p>Stamping a method's result refinement is not enough on its own, and the reason is the whole design: a
 * back-reference carries its target's postcondition as the inductive hypothesis, and it carries it <em>on the
 * call's result variable</em>. So every {@link CallRef} pointing at a specified method must have its result
 * variable refined too, or the cycle hands the recursive arm nothing to reason with. Applying both together is
 * what turns the structure into an induction.
 *
 * <p>Parameters are refined the same way and for the same reason: {@link PathFacts} admits a parameter's
 * refinement as a hypothesis on every branch, which is how a precondition reaches the arms that need it.
 */
public final class Specification {

    private Specification() {}

    /**
     * Returns a copy of {@code graph} with the given refinements applied.
     *
     * @param resultPredicates predicate per {@link Node#id()}, written over {@link SymExpr#self()}
     * @param paramPredicates  predicate per {@code nodeId + "#" + paramName}, written over {@link SymExpr#self()}
     */
    public static ReceiptGraph apply(ReceiptGraph graph,
                                     Map<String, SymExpr> resultPredicates,
                                     Map<String, SymExpr> paramPredicates) {
        List<Node> out = new ArrayList<>(graph.roots().size());
        for (Node node : graph.roots()) {
            SymExpr resultPredicate = resultPredicates.get(node.id());

            List<Param> params = new ArrayList<>(node.params().size());
            for (Param p : node.params()) {
                SymExpr predicate = paramPredicates.get(node.id() + "#" + p.name());
                params.add(predicate == null ? p
                        : new Param(p.name(), Sort.refined(p.sort().name(), predicate)));
            }

            Var resultVar = resultPredicate == null ? node.resultVar()
                    : new Var(node.resultVar().name(),
                              refineValueArm(node.resultVar().sort(), resultPredicate));

            List<Branch> branches = new ArrayList<>(node.branches().size());
            for (Branch branch : node.branches()) {
                branches.add(new Branch(
                        branch.guard(), branch.completion(), branch.initialReceipts(),
                        refineCalls(branch.calls(), resultPredicates)));
            }

            out.add(new Node(node.id(), node.displayName(), params, resultVar, branches, node.synthetic()));
        }
        return new ReceiptGraph(out);
    }

    /** Convenience for the common case of result refinements only. */
    public static ReceiptGraph apply(ReceiptGraph graph, Map<String, SymExpr> resultPredicates) {
        return apply(graph, resultPredicates, Map.of());
    }

    /**
     * Gives each call pointing at a specified method the callee's postcondition, on the variable naming its
     * result. This is the inductive hypothesis, and it is not assumed by anyone — it follows from the edge.
     */
    private static List<CallRef> refineCalls(List<CallRef> calls, Map<String, SymExpr> resultPredicates) {
        List<CallRef> out = new ArrayList<>(calls.size());
        for (CallRef call : calls) {
            SymExpr predicate = resultPredicates.get(call.targetId());
            out.add(predicate == null ? call
                    : new CallRef(call.targetId(), call.argBindings(),
                                  new Var(call.resultVar().name(),
                                          refineValueArm(call.resultVar().sort(), predicate))));
        }
        return out;
    }

    /**
     * Attaches a predicate to the value arm of a result sort, leaving the {@code Throwable} arm alone.
     *
     * <p>{@code Sort.refined} builds a sort carrying only a name and a predicate, so refining a union directly
     * would keep the name {@code "Int | Throwable"} and silently discard the arms — after which the issuer sees a
     * refined non-union and obliges the predicate on throwing paths too. Refining the arm instead yields
     * {@code Int[@>=1] | Throwable}, which reads as what was meant: when it returns normally the result is at
     * least 1, and it may instead throw.
     *
     * <p>A sort with no throwable arm is refined whole, which is the Pontif-drafted case and the one that was
     * always here.
     */
    private static Sort refineValueArm(Sort sort, SymExpr predicate) {
        List<Sort> arms = sort.unionBranches();
        if (arms == null) {
            return Sort.refined(sort.name(), predicate);
        }
        List<Sort> refined = new ArrayList<>(arms.size());
        for (Sort arm : arms) {
            refined.add(JavaSorts.THROWABLE.name().equals(arm.name())
                    ? arm
                    : Sort.refined(arm.name(), predicate));
        }
        return Sort.union(refined);
    }
}
