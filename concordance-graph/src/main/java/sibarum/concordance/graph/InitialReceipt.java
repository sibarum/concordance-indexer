package sibarum.concordance.graph;

import sibarum.cardinal.core.symbolic.SymExpr;

/**
 * A fact transcribed deterministically from source, holding on one {@link Branch}: a body equation
 * ({@code r_0 = n_0 * r_1}), or a guard restated as a positive assertion ({@code n_0 > 0}).
 *
 * <p>"Transcribed" is the whole constraint on this type. The drafter does no reasoning, so an initial receipt is
 * never derived, inferred, or simplified — if it is not visible in the source it does not become one. Anything
 * concluded <em>about</em> the graph is a closing receipt, produced by an issuer, living outside the graph.
 */
public record InitialReceipt(SymExpr claim) {

    public InitialReceipt {
        if (claim == null) {
            throw new IllegalArgumentException("InitialReceipt claim must be non-null");
        }
    }

    @Override
    public String toString() {
        return "{ " + claim + " }";
    }
}
