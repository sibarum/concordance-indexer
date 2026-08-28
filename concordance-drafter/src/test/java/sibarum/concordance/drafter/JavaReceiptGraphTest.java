package sibarum.concordance.drafter;

import org.junit.jupiter.api.Test;
import sibarum.cardinal.core.symbolic.BoundAnalysis;
import sibarum.cardinal.core.symbolic.SymExpr;
import sibarum.cardinal.core.types.Sort;
import sibarum.cardinal.receipts.*;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A Java-shaped receipt-graph, built in Cardinal's own types, run through Cardinal's own issuer and notary.
 *
 * <p>The point is that no second implementation of anything is involved. The graph is
 * {@link sibarum.cardinal.receipts.ReceiptGraph}; the claims are closed by {@link BuiltinIssuer} and checked by
 * {@link Notary}. Only the node ids and the sorts are Concordance's, and those are the two things Cardinal has no
 * view of.
 */
class JavaReceiptGraphTest {

    private static final MethodKey FACTORIAL =
            MethodKey.of("sibarum.demo.Maths", "factorial", "int");

    private static SymExpr var(String n) { return SymExpr.var(n); }
    private static SymExpr lit(long v) { return SymExpr.lit(v); }
    private static SymExpr cmp(SymExpr l, SymExpr.CmpOp op, SymExpr r) { return SymExpr.cmp(l, op, r); }

    /**
     * The graph a Java drafter should emit for:
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
        Sort nonNegative = JavaSorts.refined("int", cmp(SymExpr.self(), SymExpr.CmpOp.GE, lit(0)));
        Sort atLeastOne = JavaSorts.refined("int", cmp(SymExpr.self(), SymExpr.CmpOp.GE, lit(1)));

        // The guard clause that throws is where the parameter refinement came from.
        Branch rejects = Branch.throwing(cmp(var("n_0"), SymExpr.CmpOp.LT, lit(0)));

        Branch base = Branch.of(
                Optional.of(cmp(var("n_0"), SymExpr.CmpOp.EQ, lit(0))),
                List.of(new InitialReceipt(cmp(var("r_0"), SymExpr.CmpOp.EQ, lit(1)))),
                List.of());

        // SymExpr has no subtraction, so n - 1 is add(n, -1).
        CallRef recurse = new CallRef(
                FACTORIAL.canonical(),
                List.of(SymExpr.add(var("n_0"), lit(-1))),
                new Var("r_1", atLeastOne));

        Branch step = Branch.of(
                Optional.of(cmp(var("n_0"), SymExpr.CmpOp.GT, lit(0))),
                List.of(new InitialReceipt(
                        cmp(var("r_0"), SymExpr.CmpOp.EQ, SymExpr.mul(var("n_0"), var("r_1"))))),
                List.of(recurse));

        Node node = Node.identified(
                FACTORIAL.canonical(), FACTORIAL.methodName(),
                List.of(new Param("n_0", nonNegative)),
                new Var("r_0", atLeastOne),
                List.of(rejects, base, step));

        return new ReceiptGraph(List.of(node));
    }

    @Test
    void aJavaNodeIdPinsOneOverloadWhereAFunctionNameWouldNot() {
        ReceiptGraph graph = factorialGraph();
        Node node = graph.roots().get(0);

        assertEquals("sibarum.demo.Maths#factorial(int)", node.id());
        assertEquals("factorial", node.displayName(), "the label stays readable");

        CallRef recurse = node.branches().get(2).calls().get(0);
        assertTrue(graph.isBackReference(recurse), "a call into the drafted set is an edge");
        assertEquals(Optional.of(node), graph.target(recurse),
                "and it resolves to exactly one node, which is what makes the cycle mean something");
    }

    @Test
    void overloadsAndSameNamedMethodsElsewhereGetDistinctIds() {
        assertNotEquals(MethodKey.of("A", "f", "int").canonical(),
                MethodKey.of("A", "f", "long").canonical(), "overloads differ");
        assertNotEquals(MethodKey.of("A", "f", "int").canonical(),
                MethodKey.of("B", "f", "int").canonical(), "declaring type matters");
    }

    @Test
    void aThrowingGuardClauseCarriesAPreconditionAndNoResult() {
        Node node = factorialGraph().roots().get(0);
        Branch rejects = node.branches().get(0);

        assertTrue(rejects.isThrowing());
        assertTrue(rejects.guard().isPresent(), "a throwing path still states its condition");
        assertTrue(rejects.initialReceipts().isEmpty(), "and has no result to transcribe");
        assertEquals(2, node.returningBranches().size(), "two of the three paths produce a value");
    }

    /**
     * Cardinal's issuer closes what it can on a Java-drafted graph, and Cardinal's notary accepts its own
     * closings. Nothing here is a Concordance reimplementation.
     */
    @Test
    void cardinalsIssuerAndNotaryWorkOnAJavaDraftedGraph() {
        ReceiptGraph graph = factorialGraph();

        List<ClosingReceipt> closings = BuiltinIssuer.close(graph);
        assertFalse(closings.isEmpty(), "the built-in issuer should discharge at least one obligation");

        for (ClosingReceipt receipt : closings) {
            Notary.Verdict verdict = Notary.hypothesisSupported(graph, receipt);
            assertTrue(verdict.accepted(),
                    () -> "the notary rejected its own issuer's closing: " + verdict.reason());
        }
    }

    @Test
    void theNotaryRefutesAFalseClaimAboutAJavaDraftedPath() {
        ReceiptGraph graph = factorialGraph();

        // Path index 2 is the recursive arm. r_0 < 0 is false there: n_0 >= 1 and r_1 >= 1.
        ClosingReceipt lie = new ClosingReceipt(
                "<test>",
                cmp(var("r_0"), SymExpr.CmpOp.LT, lit(0)),
                new GraphReference(0, 2),
                null);
        assertFalse(Notary.hypothesisSupported(graph, lie).accepted(),
                "the path facts entail r_0 >= 1, which refutes r_0 < 0");

        ClosingReceipt truth = new ClosingReceipt(
                "<test>",
                cmp(var("r_0"), SymExpr.CmpOp.GE, lit(1)),
                new GraphReference(0, 2),
                null);
        assertTrue(Notary.hypothesisSupported(graph, truth).accepted(),
                "and r_0 >= 1 survives, carried by the cycle's inductive hypothesis");
    }

    @Test
    void skeletonMatchingNoLongerRequiresPontifIr() {
        ReceiptGraph graph = factorialGraph();
        assertTrue(Notary.skeletonMatches(graph, factorialGraph()),
                "a Java drafter redrafts and the notary compares, with no IrModule in sight");
        assertFalse(Notary.skeletonMatches(graph, new ReceiptGraph(List.of())),
                "a graph that disagrees with a fresh draft is stale or tampered");
    }

    /**
     * The soundness case behind {@link JavaSorts}. Cardinal routes discharge by sort name: the integer engine may
     * cut {@code >0} to {@code >=1}, and the decimal engine must not, because {@code 0.5} exists. A Java
     * {@code double} mapped to any name other than {@code Decimal} would be reasoned about with the integer cut.
     */
    @Test
    void javaDoublesRouteToDenseReasoningAndJavaIntsToDiscrete() {
        assertEquals("Decimal", JavaSorts.of("double").name(), "the name is what Cardinal routes on");
        assertEquals("Decimal", JavaSorts.of("float").name());
        assertEquals("Decimal", JavaSorts.of("java.math.BigDecimal").name());
        assertEquals("Int", JavaSorts.of("int").name());
        assertEquals("Int", JavaSorts.of("long").name());
        assertEquals("Int", JavaSorts.of("char").name(), "code points are integers");

        List<SymExpr> positive = List.of(cmp(var("x"), SymExpr.CmpOp.GT, lit(0)));
        SymExpr atLeastOne = cmp(var("x"), SymExpr.CmpOp.GE, lit(1));

        assertTrue(BoundAnalysis.discharge(BoundAnalysis.Domain.INT, positive, atLeastOne),
                "over the integers, x > 0 implies x >= 1");
        assertFalse(BoundAnalysis.discharge(BoundAnalysis.Domain.DECIMAL, positive, atLeastOne),
                "densely it does not - 0.5 is the counterexample");
    }

    @Test
    void unknownReferenceTypesStayOpaqueRatherThanGuessingADomain() {
        Sort date = JavaSorts.of("java.time.LocalDate");
        assertEquals("java.time.LocalDate", date.name());
        assertFalse(JavaSorts.isNumeric(date), "we know nothing about it, so it licenses no arithmetic");
    }

    @Test
    void cardinalsPrinterRendersAJavaDraftedGraph() {
        String printed = ReceiptGraphPrinter.print(factorialGraph());
        assertTrue(printed.contains("factorial"), printed);
        assertTrue(printed.contains("n_0"), printed);
    }
}
