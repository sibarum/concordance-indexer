package sibarum.concordance.index;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every declaration of one name, as one answer.
 *
 * <p>A name is what someone typed, so a name is what a search answers with: {@code draw} is one result whether
 * it is declared once or overloaded across a hierarchy, and {@code Vector3d} is one result however many
 * constructors stand beside its class. Choosing a group with one declaration goes straight to it; choosing one
 * with several asks which — that is where overloads, constructors, and same-named types in different packages are
 * told apart, rather than in a list where they bury everything else.
 *
 * <p>Every declaration in a group has the same name, so the same {@link Names rank}; the rank is the group's.
 *
 * @param name         the name, exactly as declared
 * @param rank         how well the name fits what was asked; 0 is exact
 * @param declarations each declaration, nearest first: by stage, then in source order
 */
public record NameGroup(String name, int rank, List<Hit<Symbol>> declarations) {

    public NameGroup {
        declarations = List.copyOf(declarations);
        if (declarations.isEmpty()) {
            throw new IllegalArgumentException("a NameGroup holds at least one declaration of " + name);
        }
    }

    /** Whether choosing this name needs a second choice: which of its declarations. */
    public boolean ambiguous() {
        return declarations.size() > 1;
    }

    /** The stage the name was first found in — the nearest of its declarations. */
    public Plan.Stage nearest() {
        return declarations.getFirst().stage();
    }

    /** The declarations alone, nearest first. */
    public List<Symbol> symbols() {
        return declarations.stream().map(Hit::item).toList();
    }

    /** {@code hits} grouped by name, groups in the order their names were first found. */
    public static List<NameGroup> of(List<Hit<Symbol>> hits) {
        Map<String, List<Hit<Symbol>>> byName = new LinkedHashMap<>();
        for (Hit<Symbol> hit : hits) {
            byName.computeIfAbsent(hit.item().name(), k -> new ArrayList<>()).add(hit);
        }
        List<NameGroup> out = new ArrayList<>();
        byName.forEach((name, declared) -> out.add(new NameGroup(name, declared.getFirst().rank(), declared)));
        return out;
    }
}
