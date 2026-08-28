package sibarum.concordance.drafter;

import org.junit.jupiter.api.Test;
import sibarum.cardinal.core.symbolic.SymExpr;
import sibarum.cardinal.receipts.*;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Drafts real Java text and checks the graph it produces. */
class JavaDrafterTest {

    private static final String MATHS = """
            package sibarum.demo;

            class Maths {
                static int factorial(int n) {
                    if (n < 0) throw new IllegalArgumentException();
                    if (n == 0) return 1;
                    return n * factorial(n - 1);
                }

                static int sumTo(int n) {
                    int total = 0;
                    int i = 0;
                    while (i < n) {
                        total = total + i;
                        i = i + 1;
                    }
                    return total;
                }

                static int twice(int n) { return n * 2; }
            }
            """;

    private static Node node(ReceiptGraph graph, String methodName) {
        return graph.roots().stream()
                .filter(n -> n.displayName().equals(methodName))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "no node named " + methodName + " in " + graph.roots().stream()
                                .map(Node::displayName).toList()));
    }

    private static String render(Optional<SymExpr> e) {
        return e.map(ReceiptGraphPrinter::renderSym).orElse("(none)");
    }

    // --- structure ---------------------------------------------------------------------------

    @Test
    void everyMethodWithABodyBecomesANode() {
        ReceiptGraph graph = JavaDrafter.draftSource(MATHS);
        List<String> names = graph.roots().stream().map(Node::displayName).toList();
        assertTrue(names.containsAll(List.of("factorial", "sumTo", "twice")), names.toString());
    }

    @Test
    void nodeIdsAreFullyQualifiedSignatures() {
        ReceiptGraph graph = JavaDrafter.draftSource(MATHS);
        assertEquals("sibarum.demo.Maths#factorial(int)", node(graph, "factorial").id());
        assertEquals("sibarum.demo.Maths#twice(int)", node(graph, "twice").id());
    }

    // --- guard clauses and recursion ----------------------------------------------------------

    @Test
    void aGuardClauseBecomesAThrowingBranchAndConstrainsWhatFollows() {
        Node factorial = node(JavaDrafter.draftSource(MATHS), "factorial");

        List<Branch> throwing = factorial.branches().stream().filter(Branch::isThrowing).toList();
        assertEquals(1, throwing.size(), "one guard clause, one throwing path");
        assertEquals("n < 0", render(throwing.get(0).guard()));
        assertTrue(throwing.get(0).initialReceipts().isEmpty(), "a throwing path states no result");

        // Every returning path holds the negation, which is where a Java precondition comes from.
        for (Branch returning : factorial.returningBranches()) {
            assertTrue(render(returning.guard()).contains("n >= 0"),
                    "surviving path should carry the negated guard, was: " + render(returning.guard()));
        }
    }

    @Test
    void theRecursiveCallIsABackReferenceOntoTheSameNode() {
        ReceiptGraph graph = JavaDrafter.draftSource(MATHS);
        Node factorial = node(graph, "factorial");

        Branch step = factorial.returningBranches().stream()
                .filter(b -> !b.calls().isEmpty())
                .findFirst().orElseThrow(() -> new AssertionError("no branch carries the recursive call"));

        CallRef call = step.calls().get(0);
        assertEquals(factorial.id(), call.targetId(), "the call resolves to this very method");
        assertTrue(graph.isBackReference(call), "so it closes a cycle");
        assertEquals(Optional.of(factorial), graph.target(call));
        assertEquals("n - 1", ReceiptGraphPrinter.renderSym(call.argBindings().get(0)),
                "the substituted argument is the descent");
    }

    @Test
    void aReturnBecomesABodyEquationOverTheHoistedCall() {
        Node factorial = node(JavaDrafter.draftSource(MATHS), "factorial");

        Branch base = factorial.returningBranches().stream()
                .filter(b -> b.calls().isEmpty())
                .findFirst().orElseThrow();
        assertEquals("r_0 == 1", ReceiptGraphPrinter.renderSym(base.initialReceipts().get(0).claim()));

        Branch step = factorial.returningBranches().stream()
                .filter(b -> !b.calls().isEmpty()).findFirst().orElseThrow();
        String equation = ReceiptGraphPrinter.renderSym(step.initialReceipts().get(0).claim());
        assertTrue(equation.startsWith("r_0 == "), equation);
        assertTrue(equation.contains("n") && equation.contains("r_1"),
                "the body equation multiplies n by the call's result: " + equation);
    }

    @Test
    void anUnresolvableCallIsMarkedRatherThanGuessedAt() {
        ReceiptGraph graph = JavaDrafter.draftSource("""
                package p;
                class C {
                    static int f(int n) { return java.lang.Math.abs(n); }
                }
                """);
        CallRef call = node(graph, "f").branches().get(0).calls().get(0);
        assertTrue(call.targetId().startsWith("?"), "an unresolved target is marked: " + call.targetId());
        assertFalse(graph.isBackReference(call), "and is not an edge, so it cannot be a wrong one");
    }

    // --- loops -------------------------------------------------------------------------------

    @Test
    void aLoopBecomesASyntheticNodeOverItsWholeScope() {
        ReceiptGraph graph = JavaDrafter.draftSource(MATHS);

        Node loop = graph.roots().stream().filter(Node::synthetic).findFirst()
                .orElseThrow(() -> new AssertionError("no synthetic loop node"));
        assertEquals("sumTo$loop$0", loop.displayName());
        assertEquals("sibarum.demo.Maths#sumTo(int)$loop$0", loop.id());

        // One parameter, and it is the frame: a struct of the loop's entire scope.
        assertEquals(1, loop.params().size());
        var frame = loop.params().get(0).sort();
        assertNotNull(frame.members(), "the frame is a structural sort");
        assertEquals(List.of("n", "total", "i"), List.copyOf(frame.members().keySet()),
                "every variable in scope is a field, in declaration order");
        assertEquals(frame, loop.resultVar().sort(), "the iteration is a function T -> T");
    }

    @Test
    void theIteratingArmCallsItselfWithAnUpdatedFrame() {
        ReceiptGraph graph = JavaDrafter.draftSource(MATHS);
        Node loop = graph.roots().stream().filter(Node::synthetic).findFirst().orElseThrow();

        Branch iterating = loop.branches().get(0);
        assertEquals("s_0.i < s_0.n", render(iterating.guard()),
                "the condition reads its variables off the frame");

        CallRef self = iterating.calls().get(iterating.calls().size() - 1);
        assertEquals(loop.id(), self.targetId(), "the back-edge is an ordinary call");
        assertTrue(graph.isBackReference(self), "so well-foundedness stays a graph property");

        String nextFrame = ReceiptGraphPrinter.renderSym(self.argBindings().get(0));
        assertTrue(nextFrame.contains("s_0.total + s_0.i"),
                "total advances by the old i: " + nextFrame);
        assertTrue(nextFrame.contains("s_0.i + 1"), "and i advances by one: " + nextFrame);
    }

    @Test
    void theExitArmReturnsTheFrameUnchanged() {
        ReceiptGraph graph = JavaDrafter.draftSource(MATHS);
        Node loop = graph.roots().stream().filter(Node::synthetic).findFirst().orElseThrow();

        Branch exiting = loop.branches().get(1);
        assertEquals("s_0.i >= s_0.n", render(exiting.guard()), "guarded by the negation");
        assertTrue(exiting.calls().isEmpty(), "and does not iterate again");
        assertEquals("f_0 == s_0",
                ReceiptGraphPrinter.renderSym(exiting.initialReceipts().get(0).claim()),
                "the fold is over, so its result is what it was handed");
    }

    @Test
    void theEnclosingMethodCallsTheLoopOnceAndReadsResultsBackOffTheFrame() {
        ReceiptGraph graph = JavaDrafter.draftSource(MATHS);
        Node sumTo = node(graph, "sumTo");

        Branch body = sumTo.returningBranches().get(0);
        CallRef loopCall = body.calls().stream()
                .filter(c -> c.targetId().contains("$loop$"))
                .findFirst().orElseThrow(() -> new AssertionError("the loop is not called: " + body.calls()));

        String entryFrame = ReceiptGraphPrinter.renderSym(loopCall.argBindings().get(0));
        assertTrue(entryFrame.contains("total") && entryFrame.contains("0"),
                "the entry frame carries the initial values: " + entryFrame);

        String equation = ReceiptGraphPrinter.renderSym(body.initialReceipts().get(0).claim());
        assertTrue(equation.contains(".total"),
                "the return reads total off the loop's result frame: " + equation);
    }

    @Test
    void aForLoopLowersLikeAWhileLoop() {
        ReceiptGraph graph = JavaDrafter.draftSource("""
                package p;
                class C {
                    static int count(int n) {
                        int c = 0;
                        for (int i = 0; i < n; i = i + 1) {
                            c = c + 2;
                        }
                        return c;
                    }
                }
                """);
        Node loop = graph.roots().stream().filter(Node::synthetic).findFirst()
                .orElseThrow(() -> new AssertionError("a for loop should synthesise a node too"));

        assertEquals(List.of("n", "c", "i"), List.copyOf(loop.params().get(0).sort().members().keySet()),
                "the for-init variable joins the frame");
        String next = ReceiptGraphPrinter.renderSym(
                loop.branches().get(0).calls().get(0).argBindings().get(0));
        assertTrue(next.contains("s_0.c + 2"), next);
        assertTrue(next.contains("s_0.i + 1"), "the update runs in the same iteration: " + next);
    }

    // --- the whole reactor -------------------------------------------------------------------

    @Test
    void draftsThisProjectWithoutFalling() throws Exception {
        java.nio.file.Path here = java.nio.file.Path.of("").toAbsolutePath();
        java.nio.file.Path root = here.getFileName().toString().startsWith("concordance-")
                ? here.getParent() : here;
        var project = sibarum.concordance.project.MavenProjectReader.read(root);

        ReceiptGraph graph = JavaDrafter.draft(project);
        assertFalse(graph.roots().isEmpty(), "drafting this project should produce nodes");
        assertTrue(graph.roots().stream().allMatch(n -> !n.branches().isEmpty()),
                "every node is well-formed");

        // Every id is unique, so no back-reference is ambiguous anywhere in the reactor.
        long distinct = graph.roots().stream().map(Node::id).distinct().count();
        assertEquals(graph.roots().size(), distinct,
                "ids collide, so some back-reference would be ambiguous");
    }
}
