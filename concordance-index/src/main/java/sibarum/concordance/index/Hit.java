package sibarum.concordance.index;

import java.util.List;

/**
 * One answer from a staged search: the thing found, how well it fits, and the stage that found it.
 *
 * <p>A progressive search cannot take back what it has already reported, so it does not try to: every match is
 * reported with its rank (see {@link Names} — 0 is exact, larger is looser), and the caller decides what to show.
 * A browsing view sorts by rank as hits arrive; a navigating one wants only {@link #bestTier}, which can tighten
 * as later stages turn up better fits.
 *
 * @param item  a {@link Symbol} or {@link Reference}, as the query produces
 * @param rank  how well it fits what was asked; 0 is exact
 * @param stage the stage of the {@link Plan} it was found in
 */
public record Hit<T>(T item, int rank, Plan.Stage stage) {

    /** The hits at the best rank any of them reached, in the order given. */
    public static <T> List<Hit<T>> bestTier(List<Hit<T>> hits) {
        int best = hits.stream().mapToInt(Hit::rank).min().orElse(0);
        return hits.stream().filter(h -> h.rank() == best).toList();
    }
}
