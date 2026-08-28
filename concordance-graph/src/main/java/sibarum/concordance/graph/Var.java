package sibarum.concordance.graph;

import sibarum.cardinal.core.types.Sort;

/**
 * A symbolic variable standing for a value on some path — a method's result ({@code r_0}), or the result of a
 * sub-call ({@code r_1}). The sort carries the refinement, when one is known.
 */
public record Var(String name, Sort sort) {

    public Var {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Var name must be non-empty");
        }
        if (sort == null) {
            throw new IllegalArgumentException("Var sort must be non-null");
        }
    }

    @Override
    public String toString() {
        return name + ":" + sort.name();
    }
}
