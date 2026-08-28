package sibarum.concordance.drafter;

import sibarum.cardinal.core.symbolic.SymExpr;
import sibarum.cardinal.core.types.Sort;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A loop's scope, as an anonymous inline struct.
 *
 * <p>This is what lets impure code fit an inductive model. A Java loop has no callee to point a back-reference
 * at, and it mutates whatever is in scope; so the loop is treated as an inline anonymous function that folds, and
 * both its argument and its result are one struct holding the loop's <em>whole</em> scope. Each iteration is then
 * a function {@code T -> T}, the back-edge is an ordinary call, and mutation has become state threading:
 *
 * <pre>{@code
 * int total = 0, i = 0;
 * while (i < n) { total = total + i; i = i + 1; }
 * return total;
 * }</pre>
 *
 * lowers to a synthetic node over {@code T = { total: Int, i: Int, n: Int }} with two arms — the iterating arm,
 * guarded by the condition and calling itself with an updated {@code T}; and the exit arm, guarded by the
 * negation, whose result is the frame unchanged. The enclosing method calls it once with the entry frame and
 * reads {@code total} back off the result.
 *
 * <p>Because the frame is the entire scope rather than the variables a particular analysis thinks are involved,
 * the same shape covers a map, a fold, a filter, an early exit, and a loop that scribbles on five unrelated
 * locals. Nothing needs to classify the loop first.
 *
 * <p>Field order is declaration order, kept stable, because {@link Sort#structural} preserves insertion order and
 * two drafts of unchanged source must produce identical graphs.
 */
final class LoopFrame {

    /** The struct's field name for each Java variable, and the sort it carries. */
    private final Map<String, Sort> fields;
    private final String typeName;
    private final Sort sort;

    LoopFrame(String typeName, Map<String, Sort> fields) {
        this.typeName = typeName;
        this.fields = new LinkedHashMap<>(fields);
        this.sort = Sort.structural(typeName, this.fields);
    }

    Sort sort() {
        return sort;
    }

    String typeName() {
        return typeName;
    }

    java.util.Set<String> names() {
        return fields.keySet();
    }

    boolean isEmpty() {
        return fields.isEmpty();
    }

    /**
     * Bindings that read every field off {@code frame} — what the loop body sees on entry to an iteration, and
     * what the enclosing method sees after the loop returns.
     */
    Map<String, SymExpr> readsFrom(SymExpr frame) {
        Map<String, SymExpr> out = new LinkedHashMap<>();
        for (String name : fields.keySet()) {
            out.put(name, SymExpr.fieldAccess(frame, name));
        }
        return out;
    }

    /**
     * A frame value built from {@code terms}, one per field.
     *
     * <p>A field the caller has no term for keeps its previous value from {@code fallback}, which is how a loop
     * body that only touches two of five locals still produces a complete frame.
     */
    SymExpr build(Map<String, SymExpr> terms, Map<String, SymExpr> fallback) {
        Map<String, SymExpr> members = new LinkedHashMap<>();
        for (String name : fields.keySet()) {
            SymExpr term = terms.get(name);
            if (term == null) {
                term = fallback.get(name);
            }
            if (term == null) {
                term = SymExpr.var(name);
            }
            members.put(name, term);
        }
        return SymExpr.record(typeName, members);
    }
}
