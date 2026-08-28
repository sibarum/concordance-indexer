/**
 * Java source to Cardinal receipt-graph.
 *
 * <p>A receipt-graph is a control-logic graph whose nodes are call sites, whose edges are calls, and whose cycles
 * carry inductive hypotheses. Cardinal defines it — see {@code pontif-framework/docs/receipt-graph.md} — and owns
 * the types, the notary that refutes claims about them, and the issuers that make those claims. There is exactly
 * one of each. Concordance is a <em>second drafter</em> for that graph, reading Java where
 * {@link sibarum.cardinal.receipts.Drafter} reads Pontif IR.
 *
 * <p>Nothing here redefines a graph type. What lives here is the two things Java needs and Cardinal has no view
 * of:
 *
 * <ul>
 *   <li>{@link sibarum.concordance.drafter.MethodKey} — the identity a Java node is registered under. Cardinal's
 *       node id is an opaque string precisely so each drafter can be as exact as its language permits; Java
 *       composes a fully-qualified signature, so a back-reference lands on one overload rather than several.
 *   <li>{@link sibarum.concordance.drafter.JavaSorts} — Java types as Cardinal sorts. The sort <em>name</em>
 *       decides which reasoning domain an obligation is routed to, so this mapping is a soundness surface rather
 *       than a convenience.
 * </ul>
 *
 * <p>Where Java needed the graph to be shaped differently, Cardinal was changed rather than shadowed: a branch
 * now records whether it returns or throws, because a Java guard clause carries a precondition and no result; and
 * a node records whether a drafter synthesised it, which iteration lowering already needed.
 */
package sibarum.concordance.drafter;
