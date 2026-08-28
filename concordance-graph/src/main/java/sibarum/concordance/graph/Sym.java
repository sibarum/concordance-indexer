package sibarum.concordance.graph;

import sibarum.cardinal.core.symbolic.SymExpr;
import sibarum.cardinal.core.types.Sort;

/**
 * Infix rendering for {@link SymExpr} and {@link Sort}.
 *
 * <p>{@code SymExpr} is a record hierarchy, so its default {@code toString} is the constructor form —
 * {@code Cmp[left=Var[name=n_0], op=GT, right=Lit[value=0]]} where {@code n_0 > 0} was meant. A graph intended to
 * be read needs the infix form.
 *
 * <p>Cardinal's own {@code ReceiptGraphPrinter} exposes public renderers that would do this, but reaching for
 * them would add {@code cardinal-receipts} — and transitively {@code cardinal-ir} and {@code cardinal-predicates}
 * — to this module for cosmetics alone, and would put two types named {@code ReceiptGraph} on the classpath.
 * Fifty lines is the cheaper trade. The notation deliberately matches Cardinal's so diagnostics from the two
 * tools read the same.
 */
public final class Sym {

    private Sym() {}

    /** Infix form, parenthesised only where precedence requires it. */
    public static String render(SymExpr e) {
        return switch (e) {
            case SymExpr.Var v -> v.name();
            case SymExpr.Lit v -> String.valueOf(v.value());
            case SymExpr.Frac v -> v.num() + "/" + v.denom();
            case SymExpr.Dec v -> v.value().toPlainString();
            case SymExpr.Chr v -> "'" + new String(Character.toChars(v.codePoint())) + "'";
            case SymExpr.Str v -> "\"" + v.value() + "\"";
            case SymExpr.Bool v -> String.valueOf(v.value());
            case SymExpr.Self ignored -> "@";
            // SymExpr has no subtraction, so a drafter writes n - 1 as add(n, -1). Render it back the way the
            // source read, or every diagnostic says "n_0 + -1".
            case SymExpr.Add a when a.right() instanceof SymExpr.Lit(long v) && v < 0 ->
                    group(a.left()) + " - " + (v == Long.MIN_VALUE ? "(" + v + ")" : String.valueOf(-v));
            case SymExpr.Add a -> group(a.left()) + " + " + group(a.right());
            case SymExpr.Mul m -> group(m.left()) + " * " + group(m.right());
            case SymExpr.Pow p -> group(p.base()) + "^" + group(p.exponent());
            // A refinement predicate is written tightly against its subject — [Int:@>=0], as Cardinal writes it.
            case SymExpr.Cmp c when c.left() instanceof SymExpr.Self ->
                    "@" + op(c.op()) + render(c.right());
            case SymExpr.Cmp c -> render(c.left()) + " " + op(c.op()) + " " + render(c.right());
            case SymExpr.And a -> group(a.left()) + " && " + group(a.right());
            case SymExpr.Or o -> group(o.left()) + " || " + group(o.right());
            case SymExpr.FieldAccess f -> render(f.base()) + "." + f.fieldName();
            // Shapes a Java drafter does not currently emit; rendered rather than hidden, so an unexpected
            // one is visible in a diagnostic instead of silently blank.
            default -> e.toString();
        };
    }

    /** A sort as {@code Int} or, when refined, {@code [Int:@>=0]}. */
    public static String render(Sort sort) {
        if (sort == null) return "?";
        return sort.isRefined()
                ? "[" + sort.name() + ":" + render(sort.predicate()) + "]"
                : sort.name();
    }

    private static String op(SymExpr.CmpOp op) {
        return switch (op) {
            case LT -> "<";
            case LE -> "<=";
            case GT -> ">";
            case GE -> ">=";
            case EQ -> "==";
            case NE -> "!=";
        };
    }

    /** Parenthesises a compound operand so {@code (a + b) * c} does not read as {@code a + b * c}. */
    private static String group(SymExpr e) {
        boolean compound = e instanceof SymExpr.Add
                || e instanceof SymExpr.Mul
                || e instanceof SymExpr.And
                || e instanceof SymExpr.Or
                || e instanceof SymExpr.Cmp;
        return compound ? "(" + render(e) + ")" : render(e);
    }
}
