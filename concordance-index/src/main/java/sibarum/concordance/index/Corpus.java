package sibarum.concordance.index;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An {@link Index} cut up by source, which is the unit a staged search walks: a search visits one source at a
 * time, in the order its {@link Plan} puts them, and can be paused or cancelled between any two.
 *
 * <p>Immutable, like the index it is cut from, so a search can go on reading one on its own thread while the
 * editor synchronizes the next. Cutting costs one pass over the index; {@link ProjectIndex#corpus} keeps the cut
 * until the index changes.
 */
public final class Corpus {

    /** What one source contributed: its declarations and its uses, in source order. */
    public record Slice(Source source, List<Symbol> symbols, List<Reference> references) {

        public Slice {
            symbols = List.copyOf(symbols);
            references = List.copyOf(references);
        }
    }

    private final Index index;
    private final Map<Source, Slice> slices;

    private Corpus(Index index, Map<Source, Slice> slices) {
        this.index = index;
        this.slices = slices;
    }

    /** {@code index} by source, sources in the order they first appear in it. */
    public static Corpus of(Index index) {
        Map<Source, List<Symbol>> symbols = new LinkedHashMap<>();
        Map<Source, List<Reference>> references = new LinkedHashMap<>();
        for (Symbol s : index.symbols()) {
            symbols.computeIfAbsent(s.source(), k -> new ArrayList<>()).add(s);
            references.putIfAbsent(s.source(), new ArrayList<>());
        }
        for (Reference r : index.references()) {
            symbols.putIfAbsent(r.at().source(), new ArrayList<>());
            references.computeIfAbsent(r.at().source(), k -> new ArrayList<>()).add(r);
        }
        Map<Source, Slice> slices = new LinkedHashMap<>();
        for (Map.Entry<Source, List<Symbol>> e : symbols.entrySet()) {
            slices.put(e.getKey(), new Slice(e.getKey(), e.getValue(), references.get(e.getKey())));
        }
        return new Corpus(index, slices);
    }

    /** The whole index, for a query that needs to see everything before it can say anything; see {@link Query}. */
    public Index index() {
        return index;
    }

    /** Every source that contributed anything, in index order. */
    public Collection<Source> sources() {
        return slices.keySet();
    }

    public Optional<Slice> slice(Source source) {
        return Optional.ofNullable(slices.get(source));
    }
}
