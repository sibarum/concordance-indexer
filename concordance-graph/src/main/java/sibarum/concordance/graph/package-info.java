/**
 * The receipt-graph as data.
 *
 * <p>A receipt-graph is a control-logic graph whose nodes are methods, whose edges are calls, and whose cycles
 * carry inductive hypotheses. Cardinal defines the idea — see {@code pontif-framework/docs/receipt-graph.md} —
 * and supplies the symbolic vocabulary ({@link sibarum.cardinal.core.symbolic.SymExpr},
 * {@link sibarum.cardinal.core.types.Sort}) and the reasoning that discharges obligations. These types are ours,
 * shaped for Java rather than for Pontif, and the two remain interoperable without either owing the other
 * compatibility.
 *
 * <p>Three departures from Cardinal's shape, each forced by Java:
 *
 * <ul>
 *   <li>{@link sibarum.concordance.graph.MethodKey} keys a node by declaring type, name and erased parameter
 *       types. Cardinal keys by function name and defers overload pinning; Java cannot defer it, because a
 *       back-reference must land on exactly one node for the cycle to mean anything.
 *   <li>{@link sibarum.concordance.graph.Branch.Completion} distinguishes a path that returns from one that
 *       throws. A throwing path is how Java writes a precondition, and it carries a guard but no result equation.
 *   <li>{@link sibarum.concordance.graph.Node#synthetic()} marks a node the drafter invented. Java loops have no
 *       callee to point back at, so a loop lowers to a synthetic tail-recursive node and its back-edge becomes an
 *       ordinary call — keeping well-foundedness a property of the graph rather than a separate proof rule.
 * </ul>
 *
 * <p>Nothing in this package reasons. Facts inside the graph are transcribed from source; conclusions about it are
 * closing receipts, external, produced by issuers.
 */
package sibarum.concordance.graph;
