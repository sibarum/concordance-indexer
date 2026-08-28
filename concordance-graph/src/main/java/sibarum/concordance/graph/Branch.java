package sibarum.concordance.graph;

import sibarum.cardinal.core.symbolic.SymExpr;

import java.util.List;
import java.util.Optional;

/**
 * One path through a {@link Node} — the Java analogue of a match arm.
 *
 * <p>Branches are alternatives, never conjuncts: a closing receipt referencing one branch is checked against that
 * branch's facts alone.
 *
 * <p>{@link #completion} is the one place this type departs from Cardinal's, and it departs because Java does.
 * A Pontif arm evaluates to a value, so every arm ends in a body equation. A Java path can instead end by
 * throwing, and a throwing path is not a degenerate returning path — it is how Java writes down a precondition:
 *
 * <pre>{@code
 * static int factorial(int n) {
 *     if (n < 0) throw new IllegalArgumentException();   // THROW: no result, constrains what survives
 *     ...                                               // RETURN: n >= 0 holds here
 * }
 * }</pre>
 *
 * <p>A {@link Completion#THROW} branch therefore carries a guard and no result equation, and the drafter is
 * entitled to restate the negation of that guard on the paths that survive it. That negation is transcription,
 * not inference: it is the meaning of the {@code if}, visible in the source.
 */
public record Branch(
        Optional<SymExpr> guard,
        Completion completion,
        List<InitialReceipt> initialReceipts,
        List<CallRef> calls) {

    /** How a path leaves the method. */
    public enum Completion {
        /** Produces the method's result; the path carries a body equation for it. */
        RETURN,
        /** Leaves by throwing; the path produces no result, and its guard constrains the surviving paths. */
        THROW
    }

    public Branch {
        if (guard == null) {
            throw new IllegalArgumentException(
                    "Branch guard must be a non-null Optional (use Optional.empty() for an unconditional path)");
        }
        if (completion == null) {
            throw new IllegalArgumentException("Branch completion must be non-null");
        }
        initialReceipts = List.copyOf(initialReceipts);
        calls = List.copyOf(calls);
    }

    /** An unconditional returning path — a body with no branching above it. */
    public static Branch returning(List<InitialReceipt> receipts, List<CallRef> calls) {
        return new Branch(Optional.empty(), Completion.RETURN, receipts, calls);
    }

    /** A guarded returning path. */
    public static Branch returning(SymExpr guard, List<InitialReceipt> receipts, List<CallRef> calls) {
        return new Branch(Optional.of(guard), Completion.RETURN, receipts, calls);
    }

    /** A guard clause that throws: the guard held, so the method did not return. */
    public static Branch throwing(SymExpr guard) {
        return new Branch(Optional.of(guard), Completion.THROW, List.of(), List.of());
    }

    public boolean isThrowing() {
        return completion == Completion.THROW;
    }
}
