package sibarum.concordance.graph;

import java.util.List;

/**
 * Renders a {@link ReceiptGraph} as an indented tree.
 *
 * <p>This exists to be read by people and asserted on by tests. It is not a serialisation format: the shape of a
 * reference into the graph is deliberately still thin, and pinning a wire format now would freeze it.
 *
 * <p>ASCII only, deliberately. Box-drawing characters render as mojibake on a Windows console under the default
 * code page, and this output is headed for a terminal.
 */
public final class ReceiptGraphPrinter {

    private ReceiptGraphPrinter() {}

    public static String print(ReceiptGraph graph) {
        StringBuilder out = new StringBuilder();
        List<Node> roots = graph.roots();
        for (int i = 0; i < roots.size(); i++) {
            if (i > 0) out.append('\n');
            printNode(graph, roots.get(i), out);
        }
        return out.toString();
    }

    public static String print(ReceiptGraph graph, Node node) {
        StringBuilder out = new StringBuilder();
        printNode(graph, node, out);
        return out.toString();
    }

    private static void printNode(ReceiptGraph graph, Node node, StringBuilder out) {
        out.append(node.key().declaringType()).append('#').append(node.key().methodName()).append('(');
        List<Param> params = node.params();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) out.append(", ");
            out.append(params.get(i).name()).append(':').append(Sym.render(params.get(i).sort()));
        }
        out.append(")  :  ")
                .append(node.resultVar().name()).append(':').append(Sym.render(node.resultVar().sort()));
        if (node.synthetic()) out.append("   [synthetic]");
        out.append('\n');

        List<Branch> branches = node.branches();
        for (Branch b : branches) {
            out.append("  |\n");
            out.append("  +-- ").append(b.isThrowing() ? "throws" : "arm");
            b.guard().ifPresentOrElse(
                    g -> out.append(" [").append(Sym.render(g)).append(']'),
                    () -> out.append(" [unconditional]"));
            out.append('\n');

            for (CallRef call : b.calls()) {
                out.append("  |     ")
                        .append(graph.isBackReference(call) ? "(back-reference) " : "(external call) ")
                        .append(renderCall(call))
                        .append('\n');
            }
            for (InitialReceipt r : b.initialReceipts()) {
                out.append("  |     initial receipt:  { ").append(Sym.render(r.claim())).append(" }\n");
            }
        }
    }

    private static String renderCall(CallRef call) {
        StringBuilder sb = new StringBuilder(call.target().methodName()).append('(');
        List<sibarum.cardinal.core.symbolic.SymExpr> args = call.argBindings();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(Sym.render(args.get(i)));
        }
        return sb.append(") : ").append(call.resultVar().name()).toString();
    }
}
