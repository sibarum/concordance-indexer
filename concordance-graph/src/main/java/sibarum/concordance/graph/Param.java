package sibarum.concordance.graph;

import sibarum.cardinal.core.types.Sort;

/**
 * One entry in a {@link Node}'s parameter-constraint header.
 *
 * <p>The {@code sort} is where a Java method's precondition ends up. Java declares no refinements, so an
 * unrefined sort — {@code Sort.of("Int")} — is the honest default and says only "an int, nothing more known".
 * A refined sort appears when the source actually states a constraint the drafter can transcribe without
 * reasoning: a guard clause that throws, an {@code assert}, a {@code requireNonNull}, or an annotation.
 */
public record Param(String name, Sort sort) {

    public Param {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Param name must be non-empty");
        }
        if (sort == null) {
            throw new IllegalArgumentException("Param sort must be non-null");
        }
    }

    @Override
    public String toString() {
        return name + ":" + sort.name();
    }
}
