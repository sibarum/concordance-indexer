package sibarum.concordance.drafter;

import com.github.javaparser.ast.expr.*;
import sibarum.cardinal.core.symbolic.SymExpr;

import java.util.Map;
import java.util.Optional;

/**
 * Java expressions as {@link SymExpr}, and only where the translation is a transcription.
 *
 * <p>Every method here returns {@link Optional#empty()} rather than guessing. A drafter does no reasoning, so an
 * expression it cannot transcribe faithfully must produce no fact at all — an absent body equation is honest,
 * while an approximated one would be a claim nobody made. The gaps are therefore load-bearing:
 *
 * <ul>
 *   <li><b>Division.</b> {@link SymExpr} has no divide, and rewriting {@code a / b} as {@code a * b^-1} is wrong
 *       for Java integer division, which truncates. Cardinal's own drafter hoists division as an opaque call for
 *       the same reason.
 *   <li><b>Anything with a side effect</b> — assignment as an expression, {@code ++}, a method call — is not
 *       translated here. Calls are hoisted by the drafter into a {@link sibarum.cardinal.receipts.CallRef} and
 *       replaced by the variable that names their result, so by the time an expression reaches this class the
 *       calls in it are already variables.
 *   <li><b>Widening and overflow.</b> {@code int} arithmetic wraps and {@code SymExpr} integers do not, so a
 *       transcribed equation describes the mathematical result. This is a real unsoundness at the boundary and is
 *       recorded here rather than hidden: an issuer reasoning about a value near {@link Integer#MAX_VALUE} is
 *       reasoning about a program that does something else.
 * </ul>
 *
 * <p>{@code bindings} maps a Java name to the term standing for it — a frame field for a loop variable, or a
 * fresh result variable for a hoisted call. A name absent from the map becomes a plain
 * {@link SymExpr.Var} of the same name.
 */
public final class Exprs {

    private Exprs() {}

    /** Transcribes {@code expr}, or empty when it cannot be transcribed faithfully. */
    public static Optional<SymExpr> of(Expression expr, Map<String, SymExpr> bindings) {
        return switch (expr) {
            case IntegerLiteralExpr e -> longOf(e.getValue()).map(SymExpr::lit);
            case LongLiteralExpr e -> longOf(e.getValue()).map(SymExpr::lit);
            case CharLiteralExpr e -> Optional.of(SymExpr.chr(e.asChar()));
            case BooleanLiteralExpr e -> Optional.of(SymExpr.bool(e.getValue()));
            case StringLiteralExpr e -> Optional.of(SymExpr.str(e.getValue()));
            case NameExpr e -> Optional.of(resolve(e.getNameAsString(), bindings));
            case EnclosedExpr e -> of(e.getInner(), bindings);
            case UnaryExpr e -> unary(e, bindings);
            case BinaryExpr e -> binary(e, bindings);
            case FieldAccessExpr e -> of(e.getScope(), bindings)
                    .map(base -> SymExpr.fieldAccess(base, e.getNameAsString()));
            // Deliberately not translated: MethodCallExpr (hoisted before we get here), assignment, ++/--,
            // casts, array access, instanceof, lambdas, conditionals. See the class note.
            default -> Optional.empty();
        };
    }

    /** The term standing for a Java name on this path. */
    public static SymExpr resolve(String name, Map<String, SymExpr> bindings) {
        SymExpr bound = bindings.get(name);
        return bound != null ? bound : SymExpr.var(name);
    }

    private static Optional<Long> longOf(String literal) {
        String cleaned = literal.replace("_", "").replaceAll("[lL]$", "");
        try {
            if (cleaned.startsWith("0x") || cleaned.startsWith("0X")) {
                return Optional.of(Long.parseLong(cleaned.substring(2), 16));
            }
            if (cleaned.startsWith("0b") || cleaned.startsWith("0B")) {
                return Optional.of(Long.parseLong(cleaned.substring(2), 2));
            }
            return Optional.of(Long.parseLong(cleaned));
        } catch (NumberFormatException e) {
            return Optional.empty();   // a literal we cannot represent is not transcribed
        }
    }

    private static Optional<SymExpr> unary(UnaryExpr e, Map<String, SymExpr> bindings) {
        Optional<SymExpr> inner = of(e.getExpression(), bindings);
        return inner.flatMap(v -> switch (e.getOperator()) {
            case MINUS -> Optional.of(SymExpr.mul(SymExpr.lit(-1), v));
            case PLUS -> Optional.of(v);
            case LOGICAL_COMPLEMENT -> negate(v);
            default -> Optional.empty();   // ++, --, ~
        });
    }

    private static Optional<SymExpr> binary(BinaryExpr e, Map<String, SymExpr> bindings) {
        Optional<SymExpr> left = of(e.getLeft(), bindings);
        Optional<SymExpr> right = of(e.getRight(), bindings);
        if (left.isEmpty() || right.isEmpty()) {
            return Optional.empty();
        }
        SymExpr l = left.get();
        SymExpr r = right.get();
        return switch (e.getOperator()) {
            case PLUS -> Optional.of(SymExpr.add(l, r));
            case MINUS -> Optional.of(SymExpr.add(l, SymExpr.mul(SymExpr.lit(-1), r)));
            case MULTIPLY -> Optional.of(SymExpr.mul(l, r));
            case LESS -> Optional.of(SymExpr.cmp(l, SymExpr.CmpOp.LT, r));
            case LESS_EQUALS -> Optional.of(SymExpr.cmp(l, SymExpr.CmpOp.LE, r));
            case GREATER -> Optional.of(SymExpr.cmp(l, SymExpr.CmpOp.GT, r));
            case GREATER_EQUALS -> Optional.of(SymExpr.cmp(l, SymExpr.CmpOp.GE, r));
            case EQUALS -> Optional.of(SymExpr.cmp(l, SymExpr.CmpOp.EQ, r));
            case NOT_EQUALS -> Optional.of(SymExpr.cmp(l, SymExpr.CmpOp.NE, r));
            case AND -> Optional.of(SymExpr.and(l, r));
            case OR -> Optional.of(SymExpr.or(l, r));
            // DIVIDE and REMAINDER truncate; XOR/shifts/bitwise have no SymExpr form.
            default -> Optional.empty();
        };
    }

    /**
     * The negation of a transcribed condition, used for the path that a guard clause did not take.
     *
     * <p>A comparison flips. A conjunction or disjunction flips by De Morgan. Anything else has no negation this
     * class will invent, so the surviving path simply carries no restated guard.
     */
    public static Optional<SymExpr> negate(SymExpr e) {
        return switch (e) {
            case SymExpr.Cmp c -> Optional.of(SymExpr.cmp(c.left(), flip(c.op()), c.right()));
            case SymExpr.Bool b -> Optional.of(SymExpr.bool(!b.value()));
            case SymExpr.And a -> negate(a.left()).flatMap(nl -> negate(a.right()).map(nr -> SymExpr.or(nl, nr)));
            case SymExpr.Or o -> negate(o.left()).flatMap(nl -> negate(o.right()).map(nr -> SymExpr.and(nl, nr)));
            default -> Optional.empty();
        };
    }

    private static SymExpr.CmpOp flip(SymExpr.CmpOp op) {
        return switch (op) {
            case LT -> SymExpr.CmpOp.GE;
            case LE -> SymExpr.CmpOp.GT;
            case GT -> SymExpr.CmpOp.LE;
            case GE -> SymExpr.CmpOp.LT;
            case EQ -> SymExpr.CmpOp.NE;
            case NE -> SymExpr.CmpOp.EQ;
        };
    }
}
