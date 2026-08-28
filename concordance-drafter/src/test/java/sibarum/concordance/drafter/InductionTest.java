package sibarum.concordance.drafter;

import org.junit.jupiter.api.Test;
import sibarum.cardinal.core.symbolic.SymExpr;
import sibarum.cardinal.receipts.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The end of the chain: Java source in, an inductive proof out.
 *
 * <p>Drafting alone proves nothing, because Java declares no refinements. Supply one and the structure the drafter
 * built does the rest — Cardinal's issuer discharges the obligation on both arms of the recursion, and the
 * recursive arm can only be discharged because the cycle handed it the inductive hypothesis.
 */
class InductionTest {

    private static final String SOURCE = """
            package sibarum.demo;

            class Maths {
                static int factorial(int n) {
                    if (n < 0) throw new IllegalArgumentException();
                    if (n == 0) return 1;
                    return n * factorial(n - 1);
                }
            }
            """;

    private static final String FACTORIAL = "sibarum.demo.Maths#factorial(int)";

    /** {@code @ >= bound}, the shape a refinement predicate takes. */
    private static SymExpr atLeast(long bound) {
        return SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GE, SymExpr.lit(bound));
    }

    @Test
    void aDraftedGraphProvesNothingUntilARefinementArrives() {
        ReceiptGraph bare = JavaDrafter.draftSource(SOURCE);
        assertTrue(BuiltinIssuer.close(bare).isEmpty(),
                "no declared refinement means no obligation, so there is nothing to discharge");
    }

    @Test
    void withTheReturnRefinementSuppliedTheRecursionIsProvedByInduction() {
        ReceiptGraph graph = Specification.apply(
                JavaDrafter.draftSource(SOURCE),
                Map.of(FACTORIAL, atLeast(1)),                       // factorial returns at least 1
                Map.of(FACTORIAL + "#n", atLeast(0)));               // and takes a non-negative n

        List<ClosingReceipt> closings = BuiltinIssuer.close(graph);
        assertFalse(closings.isEmpty(), "the obligation r_0 >= 1 should now be attempted and discharged");

        // Cardinal's notary must accept its own issuer's closings.
        for (ClosingReceipt receipt : closings) {
            Notary.Verdict verdict = Notary.hypothesisSupported(graph, receipt);
            assertTrue(verdict.accepted(), () -> "notary rejected a closing: " + verdict.reason());
        }

        Node factorial = graph.roots().stream()
                .filter(n -> n.id().equals(FACTORIAL)).findFirst().orElseThrow();
        int recursiveArm = factorial.branches().indexOf(factorial.branches().stream()
                .filter(b -> !b.calls().isEmpty()).findFirst().orElseThrow());

        assertTrue(closings.stream().anyMatch(c -> c.reference().branchIndex() == recursiveArm),
                "the recursive arm is among the discharged branches, which is the induction step");
    }

    @Test
    void theInductiveHypothesisIsWhatMakesTheRecursiveArmGoThrough() {
        // Same graph, except the recursive call's result variable keeps no refinement — the cycle carries
        // nothing forward. The step should then fail to discharge, while the base case still succeeds.
        ReceiptGraph withHypothesis = Specification.apply(
                JavaDrafter.draftSource(SOURCE),
                Map.of(FACTORIAL, atLeast(1)),
                Map.of(FACTORIAL + "#n", atLeast(0)));

        ReceiptGraph withoutHypothesis = stripCallRefinements(withHypothesis);

        int withCount = BuiltinIssuer.close(withHypothesis).size();
        int withoutCount = BuiltinIssuer.close(withoutHypothesis).size();

        assertTrue(withCount > withoutCount,
                "removing the hypothesis the cycle supplies should cost at least one discharge; "
                        + "with=" + withCount + " without=" + withoutCount);
    }

    /** Rebuilds the graph with every call's result variable unrefined. */
    private static ReceiptGraph stripCallRefinements(ReceiptGraph graph) {
        List<Node> nodes = graph.roots().stream().map(node -> {
            List<Branch> branches = node.branches().stream().map(branch -> {
                List<CallRef> calls = branch.calls().stream()
                        .map(c -> new CallRef(c.targetId(), c.argBindings(),
                                new Var(c.resultVar().name(),
                                        sibarum.cardinal.core.types.Sort.of(c.resultVar().sort().name()))))
                        .toList();
                return new Branch(branch.guard(), branch.completion(), branch.initialReceipts(), calls);
            }).toList();
            return new Node(node.id(), node.displayName(), node.params(),
                    node.resultVar(), branches, node.synthetic());
        }).toList();
        return new ReceiptGraph(nodes);
    }
}
