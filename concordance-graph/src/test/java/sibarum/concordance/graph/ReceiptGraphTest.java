package sibarum.concordance.graph;

import org.junit.jupiter.api.Test;
import sibarum.cardinal.core.symbolic.BoundAnalysis;
import sibarum.cardinal.core.symbolic.SymExpr;
import sibarum.cardinal.core.types.Sort;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The graph types, exercised on the shape a Java factorial produces, plus the seam that matters: our graph's
 * path facts feeding Cardinal's public reasoning.
 */
class ReceiptGraphTest {

    private static final String TYPE = "sibarum.demo.Maths";
    private static final MethodKey FACTORIAL = MethodKey.of(TYPE, "factorial", "int");

    private static SymExpr var(String n) { return SymExpr.var(n); }
    private static SymExpr lit(long v) { return SymExpr.lit(v); }
    private static SymExpr cmp(SymExpr l, SymExpr.CmpOp op, SymExpr r) { return SymExpr.cmp(l, op, r); }

    /**
     * The graph a drafter should emit for:
     *
     * <pre>{@code
     * static int factorial(int n) {
     *     if (n < 0) throw new IllegalArgumentException();
     *     if (n == 0) return 1;
     *     return n * factorial(n - 1);
     * }
     * }</pre>
     */
    private static ReceiptGraph factorialGraph() {
        // The guard clause that throws refines the parameter for the paths that survive it.
        Sort nonNegativeInt = JavaSorts.refined("int", cmp(SymExpr.self(), SymExpr.CmpOp.GE, lit(0)));
        Sort atLeastOne = JavaSorts.refined("int", cmp(SymExpr.self(), SymExpr.CmpOp.GE, lit(1)));

        Branch rejects = Branch.throwing(cmp(var("n_0"), SymExpr.CmpOp.LT, lit(0)));

        Branch base = Branch.returning(
                cmp(var("n_0"), SymExpr.CmpOp.EQ, lit(0)),
                List.of(new InitialReceipt(cmp(var("r_0"), SymExpr.CmpOp.EQ, lit(1)))),
                List.of());

        // n - 1 is add(n, -1): SymExpr has no subtraction, and normalising it away here keeps the
        // linear form the reasoning layer wants.
        CallRef recurse = new CallRef(
                FACTORIAL,
                List.of(SymExpr.add(var("n_0"), lit(-1))),
                new Var("r_1", atLeastOne));

        Branch step = Branch.returning(
                cmp(var("n_0"), SymExpr.CmpOp.GT, lit(0)),
                List.of(new InitialReceipt(
                        cmp(var("r_0"), SymExpr.CmpOp.EQ, SymExpr.mul(var("n_0"), var("r_1"))))),
                List.of(recurse));

        Node node = Node.of(
                FACTORIAL,
                List.of(new Param("n_0", nonNegativeInt)),
                new Var("r_0", atLeastOne),
                List.of(rejects, base, step));

        return new ReceiptGraph(List.of(node));
    }

    @Test
    void theRecursiveCallIsABackReferenceRatherThanAnExpansion() {
        ReceiptGraph graph = factorialGraph();

        assertEquals(1, graph.roots().size(), "one method drafted means exactly one node");
        Node node = graph.roots().get(0);

        CallRef recurse = node.branches().get(2).calls().get(0);
        assertTrue(graph.isBackReference(recurse), "a call into the drafted set closes the cycle");
        assertSame(node, graph.nodeFor(recurse.target()).orElseThrow(),
                "the back-reference must land on the very same node, not a copy of it");
    }

    @Test
    void aRepeatedMethodIsRejectedRatherThanDuplicated() {
        Node node = factorialGraph().roots().get(0);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ReceiptGraph(List.of(node, node)));
        assertTrue(e.getMessage().contains("duplicate node"), e.getMessage());
    }

    @Test
    void throwingPathsAreSeparatedFromReturningOnes() {
        Node node = factorialGraph().roots().get(0);
        assertEquals(3, node.branches().size());
        assertEquals(2, node.returningBranches().size(), "the guard clause produces no result");
        assertTrue(node.branches().get(0).isThrowing());
        assertTrue(node.branches().get(0).guard().isPresent(), "a throwing path still carries its guard");
        assertTrue(node.branches().get(0).initialReceipts().isEmpty(),
                "a throwing path has no body equation to transcribe");
    }

    @Test
    void overloadsAndSameNamedMethodsOnOtherTypesStayDistinct() {
        MethodKey intVersion = MethodKey.of(TYPE, "factorial", "int");
        MethodKey longVersion = MethodKey.of(TYPE, "factorial", "long");
        MethodKey elsewhere = MethodKey.of("sibarum.other.Maths", "factorial", "int");

        assertNotEquals(intVersion, longVersion, "overloads are different nodes");
        assertNotEquals(intVersion, elsewhere, "same name on another type is a different node");
        assertEquals("sibarum.demo.Maths#factorial(int)", intVersion.canonical());
    }

    /**
     * The seam. Our graph supplies the path facts; Cardinal's public {@code BoundAnalysis} does the reasoning.
     * Nothing package-private to Cardinal's receipts module is involved.
     */
    @Test
    void theInductiveHypothesisArrivesFromTheCycleWithoutBeingAssumed() {
        Node node = factorialGraph().roots().get(0);
        Branch step = node.branches().get(2);
        PathFacts facts = PathFacts.of(node, step);

        // Nobody wrote "assume the recursive result is >= 1". It is here because the edge pointed at a node
        // whose result carries that refinement.
        assertTrue(facts.hypotheses().stream().anyMatch(h -> h.toString().contains("r_1")),
                "the back-reference must contribute its target's postcondition: " + facts.hypotheses());
        assertTrue(facts.hypotheses().contains(step.guard().orElseThrow()), "the guard holds on its own path");
        assertTrue(facts.hasDefinition(), "the returning path states what the result is");

        // And the goal gets rewritten in terms of what produced the result.
        SymExpr rewritten = facts.substituteDefinition(cmp(var("r_0"), SymExpr.CmpOp.GE, lit(0)));
        assertFalse(rewritten.toString().contains("r_0"),
                "r_0 should have been replaced by its defining expression: " + rewritten);
    }

    @Test
    void refutationRejectsAFalseClaimAndLetsATrueOneStand() {
        Node node = factorialGraph().roots().get(0);
        Branch step = node.branches().get(2);

        assertFalse(Refutation.refutes(node, step, cmp(var("r_0"), SymExpr.CmpOp.GE, lit(1))),
                "r_0 >= 1 must survive on the recursive path");
        assertTrue(Refutation.refutes(node, step, cmp(var("r_0"), SymExpr.CmpOp.LT, lit(0))),
                "r_0 < 0 must be refuted: n_0 >= 1 and r_1 >= 1 give r_0 = n_0 * r_1 >= 1");

        Branch base = node.branches().get(1);
        assertFalse(Refutation.refutes(node, base, cmp(var("r_0"), SymExpr.CmpOp.GE, lit(1))),
                "r_0 >= 1 must survive on the base case too, where r_0 = 1");
        assertTrue(Refutation.refutes(node, base, cmp(var("r_0"), SymExpr.CmpOp.GT, lit(1))),
                "r_0 > 1 must be refuted on the base case, where r_0 is exactly 1");
    }

    @Test
    void aClaimTheKernelCannotAnalyseIsNotRejectedForBeingUnfamiliar() {
        Node node = factorialGraph().roots().get(0);
        Branch step = node.branches().get(2);
        assertFalse(Refutation.refutes(node, step, SymExpr.bool(true)),
                "a non-comparison claim is not refutable, and must not be refuted by default");
    }

    /**
     * The soundness case behind {@link JavaSorts}. The integer engine may cut {@code >0} to {@code >=1}; the
     * decimal engine must not, because {@code 0.5} exists. A Java {@code double} mapped to any sort name other
     * than {@code Decimal} would be reasoned about with the integer cut, silently and wrongly.
     */
    @Test
    void javaDoublesRouteToDenseReasoningAndJavaIntsToDiscrete() {
        assertTrue(JavaSorts.isDense(JavaSorts.of("double")), "double must be dense");
        assertTrue(JavaSorts.isDense(JavaSorts.of("float")), "float must be dense");
        assertTrue(JavaSorts.isDense(JavaSorts.of("java.math.BigDecimal")), "BigDecimal must be dense");
        assertFalse(JavaSorts.isDense(JavaSorts.of("int")), "int must be discrete");
        assertFalse(JavaSorts.isDense(JavaSorts.of("long")), "long must be discrete");
        assertFalse(JavaSorts.isDense(JavaSorts.of("char")), "char is discrete: code points are integers");

        assertEquals("Decimal", JavaSorts.of("double").name(),
                "the name is what Cardinal routes on; it must be exactly Decimal");
        assertEquals("Int", JavaSorts.of("int").name());

        List<SymExpr> positive = List.of(cmp(var("x"), SymExpr.CmpOp.GT, lit(0)));
        SymExpr atLeastOne = cmp(var("x"), SymExpr.CmpOp.GE, lit(1));

        assertTrue(BoundAnalysis.discharge(BoundAnalysis.Domain.INT, positive, atLeastOne),
                "over the integers, x > 0 implies x >= 1");
        assertFalse(BoundAnalysis.discharge(BoundAnalysis.Domain.DECIMAL, positive, atLeastOne),
                "densely, x > 0 does not imply x >= 1 — 0.5 is the counterexample");
    }

    @Test
    void unknownReferenceTypesStayOpaqueRatherThanGuessingADomain() {
        Sort date = JavaSorts.of("java.time.LocalDate");
        assertEquals("java.time.LocalDate", date.name());
        assertFalse(JavaSorts.isNumeric(date), "we know nothing about it, so it licenses no arithmetic");
    }

    @Test
    void thePrinterShowsTheCycleAsABackReferenceInReadableInfix() {
        String printed = ReceiptGraphPrinter.print(factorialGraph());

        assertTrue(printed.contains("sibarum.demo.Maths#factorial(n_0:[Int:@>=0])"),
                "header shows the parameter refinement, tight against its subject:\n" + printed);
        assertTrue(printed.contains("r_0:[Int:@>=1]"), printed);
        assertTrue(printed.contains("throws [n_0 < 0]"), printed);
        assertTrue(printed.contains("arm [n_0 == 0]"), printed);
        assertTrue(printed.contains("(back-reference) factorial(n_0 - 1) : r_1"),
                "add(n_0, -1) must read as a subtraction:\n" + printed);
        assertTrue(printed.contains("initial receipt:  { r_0 == n_0 * r_1 }"), printed);

        assertFalse(printed.contains("Cmp["), "nothing should fall back to the record form:\n" + printed);
        assertTrue(printed.chars().allMatch(c -> c < 128),
                "ASCII only, so a Windows console does not mojibake it");
    }

    @Test
    void renderingParenthesisesOnlyWherePrecedenceNeedsIt() {
        assertEquals("n_0 * r_1", Sym.render(SymExpr.mul(var("n_0"), var("r_1"))));
        assertEquals("(a + b) * c",
                Sym.render(SymExpr.mul(SymExpr.add(var("a"), var("b")), var("c"))));
        assertEquals("n - 1", Sym.render(SymExpr.add(var("n"), lit(-1))));
        assertEquals("n + 1", Sym.render(SymExpr.add(var("n"), lit(1))));
        assertEquals("[Int:@>=0]",
                Sym.render(JavaSorts.refined("int", cmp(SymExpr.self(), SymExpr.CmpOp.GE, lit(0)))));
        assertEquals("Int", Sym.render(JavaSorts.of("int")));
    }
}
