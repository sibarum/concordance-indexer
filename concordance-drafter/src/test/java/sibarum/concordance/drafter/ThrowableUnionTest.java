package sibarum.concordance.drafter;

import org.junit.jupiter.api.Test;
import sibarum.cardinal.core.symbolic.SymExpr;
import sibarum.cardinal.core.types.Sort;
import sibarum.cardinal.receipts.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every method's result is {@code T | Throwable}, so a throwing path produces a result in the other arm rather
 * than producing none. See {@code docs/exceptions-as-values.md}.
 */
class ThrowableUnionTest {

    private static final String SOURCE = """
            package sibarum.demo;
            class Maths {
                static int factorial(int n) {
                    if (n < 0) throw new IllegalArgumentException();
                    if (n == 0) return 1;
                    return n * factorial(n - 1);
                }
                static double half(double x) { return x; }
            }
            """;
    private static final String FACTORIAL = "sibarum.demo.Maths#factorial(int)";

    private static Node node(ReceiptGraph graph, String name) {
        return graph.roots().stream().filter(n -> n.displayName().equals(name)).findFirst().orElseThrow();
    }

    private static SymExpr atLeast(long bound) {
        return SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GE, SymExpr.lit(bound));
    }

    @Test
    void aResultSortIsAUnionWithThrowable() {
        Node factorial = node(JavaDrafter.draftSource(SOURCE), "factorial");
        Sort result = factorial.resultVar().sort();

        assertNotNull(result.unionBranches(), "the result is a union: " + result.name());
        assertEquals(List.of("Int", "Throwable"),
                result.unionBranches().stream().map(Sort::name).toList());
        assertEquals(List.of("Int"), JavaSorts.valueArms(result).stream().map(Sort::name).toList());
    }

    @Test
    void theDeclaredTypeStillDecidesTheDomainOfTheValueArm() {
        Node half = node(JavaDrafter.draftSource(SOURCE), "half");
        Sort value = JavaSorts.valueArms(half.resultVar().sort()).get(0);

        assertEquals("Decimal", value.name(),
                "a double stays dense inside the union, or it would take integer-strict cuts");
        assertTrue(JavaSorts.isDense(value));
    }

    @Test
    void addingAThrowableArmIsIdempotent() {
        Sort once = JavaSorts.resultOf("int");
        assertSame(once, JavaSorts.orThrowable(once), "a sort that already admits a throwable is unchanged");
        assertEquals(2, once.unionBranches().size(), "and does not accumulate arms");
        assertSame(JavaSorts.THROWABLE, JavaSorts.orThrowable(JavaSorts.THROWABLE));
    }

    @Test
    void aRefinementAttachesToTheValueArmAndLeavesTheUnionIntact() {
        ReceiptGraph graph = Specification.apply(
                JavaDrafter.draftSource(SOURCE), Map.of(FACTORIAL, atLeast(1)));
        Sort result = node(graph, "factorial").resultVar().sort();

        assertNotNull(result.unionBranches(),
                "refining must not flatten the union into a refined non-union: " + result.name());
        Sort value = result.unionBranches().get(0);
        Sort throwable = result.unionBranches().get(1);
        assertTrue(value.isRefined(), "the value arm carries the predicate");
        assertFalse(throwable.isRefined(), "the throwable arm carries nothing");
        assertEquals("Throwable", throwable.name());
    }

    @Test
    void aThrowingPathIsNotObligedToProveAClaimAboutTheValueArm() {
        ReceiptGraph graph = Specification.apply(
                JavaDrafter.draftSource(SOURCE),
                Map.of(FACTORIAL, atLeast(1)),
                Map.of(FACTORIAL + "#n", atLeast(0)));

        Node factorial = node(graph, "factorial");
        int throwingIndex = -1;
        for (int i = 0; i < factorial.branches().size(); i++) {
            if (factorial.branches().get(i).isThrowing()) throwingIndex = i;
        }
        assertTrue(throwingIndex >= 0, "the guard clause is still a throwing path");

        int nodeIndex = graph.roots().indexOf(factorial);
        final int throwing = throwingIndex;
        assertTrue(BuiltinIssuer.attemptAll(graph).stream()
                        .noneMatch(a -> a.nodeIndex() == nodeIndex && a.branchIndex() == throwing),
                "no attempt is recorded against a path that produces the throwable arm");

        // and the returning paths still discharge, so nothing was lost by narrowing the obligation
        assertFalse(BuiltinIssuer.close(graph).isEmpty());
    }

    @Test
    void theInductionStillGoesThroughWithUnionResults() {
        ReceiptGraph graph = Specification.apply(
                JavaDrafter.draftSource(SOURCE),
                Map.of(FACTORIAL, atLeast(1)),
                Map.of(FACTORIAL + "#n", atLeast(0)));

        List<ClosingReceipt> closings = BuiltinIssuer.close(graph);
        assertEquals(2, closings.size(), "the base case and the induction step");
        for (ClosingReceipt receipt : closings) {
            assertTrue(Notary.hypothesisSupported(graph, receipt).accepted());
        }
    }

    /**
     * Records what is not done yet, so it is not mistaken for working.
     *
     * <p>A call's result variable is the <em>value</em> arm, not the union, because the drafter does not yet emit
     * the match that destructures a callee's result. So a callee's throwable arm is dropped rather than
     * propagated into this method's own.
     */
    @Test
    void aCallSiteDoesNotYetDestructureTheCalleesUnion() {
        Node factorial = node(JavaDrafter.draftSource(SOURCE), "factorial");
        CallRef recursive = factorial.branches().stream()
                .filter(b -> !b.calls().isEmpty()).findFirst().orElseThrow().calls().get(0);

        assertNull(recursive.resultVar().sort().unionBranches(),
                "today the call binds the value arm directly; when destructuring lands this becomes a union");
        assertEquals("Int", recursive.resultVar().sort().name());
    }
}
