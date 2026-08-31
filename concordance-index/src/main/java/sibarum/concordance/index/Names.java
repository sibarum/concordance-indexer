package sibarum.concordance.index;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * How well a name answers what somebody typed.
 *
 * <p>A caller asking an index a question often has limited information: half a name, the wrong case, the humps
 * of a long identifier and none of the letters between them. Matching only what is spelled exactly makes the
 * index useless precisely when it is most needed, so every query here matches permissively and says how well
 * each answer fits — {@link #rank} is small for a good fit and {@link #NO_MATCH} for none.
 *
 * <p>Rank is not a score to add up or a threshold to tune. It is an ordering, and its tiers are the distinct
 * ways a name can be almost right:
 *
 * <pre>
 *   0 exact                 area                → area
 *   1 exact but for case    AREA                → area
 *   2 prefix                Ind                 → IndexBuilder
 *   3 contains              Build               → IndexBuilder
 *   4 camel humps           IB, MPR             → IndexBuilder, MavenProjectReader
 *   5 subsequence           idxbld              → IndexBuilder
 * </pre>
 *
 * <p>A browsing query — "show me everything like this" — keeps all the tiers and sorts by them. A navigating
 * query — "take me to this" — keeps only {@link #bestTier}, so a caller who does know the exact name gets
 * exactly the old answer and nobody has to page past near-misses to reach the thing they named.
 */
final class Names {

    /** No fit at all. Larger than every rank, so it sorts last if it ever reaches a comparator. */
    static final int NO_MATCH = Integer.MAX_VALUE;

    private Names() {}

    /** How well {@code candidate} answers {@code query}: 0 for exact, higher for looser, {@link #NO_MATCH} for none. */
    static int rank(String candidate, String query) {
        if (candidate == null) return NO_MATCH;
        if (candidate.equals(query)) return 0;

        String name = candidate.toLowerCase(Locale.ROOT);
        String needle = query.toLowerCase(Locale.ROOT);
        if (name.equals(needle)) return 1;
        if (name.startsWith(needle)) return 2;
        if (name.contains(needle)) return 3;
        if (needle.isEmpty()) return NO_MATCH;
        if (humps(candidate).toLowerCase(Locale.ROOT).startsWith(needle)) return 4;
        if (isSubsequence(name, needle)) return 5;
        return NO_MATCH;
    }

    /** Whether {@code candidate} answers {@code query} at all. */
    static boolean matches(String candidate, String query) {
        return rank(candidate, query) != NO_MATCH;
    }

    /**
     * The items that fit {@code query} best, and only those.
     *
     * <p>An exact match, if there is one, is the whole answer; a fuzzier tier is reached only when every tighter
     * one is empty. This is what keeps permissive matching from making a precise question worse.
     */
    static <T> List<T> bestTier(Collection<T> items, Function<T, String> name, String query) {
        int best = NO_MATCH;
        for (T item : items) {
            best = Math.min(best, rank(name.apply(item), query));
            if (best == 0) break;
        }
        if (best == NO_MATCH) return List.of();

        List<T> kept = new ArrayList<>();
        for (T item : items) {
            if (rank(name.apply(item), query) == best) kept.add(item);
        }
        return kept;
    }

    /** The capitalized word starts of a name: {@code MavenProjectReader} → {@code MPR}. */
    private static String humps(String name) {
        StringBuilder humps = new StringBuilder();
        boolean atWordStart = true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_' || c == '$') {
                atWordStart = true;
            } else if (atWordStart || Character.isUpperCase(c)) {
                humps.append(c);
                atWordStart = false;
            }
        }
        return humps.toString();
    }

    /** Whether every character of {@code needle} appears in {@code name}, in order. Both already lowercased. */
    private static boolean isSubsequence(String name, String needle) {
        int at = 0;
        for (int i = 0; i < name.length() && at < needle.length(); i++) {
            if (name.charAt(i) == needle.charAt(at)) at++;
        }
        return at == needle.length();
    }
}
