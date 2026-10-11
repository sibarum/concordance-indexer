package sibarum.concordance.project;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Maven version ordering and version ranges, enough to pick a version out of what a local repository holds.
 *
 * <p>The ordering follows Maven's {@code ComparableVersion} in its main lines: a version is split into numbers
 * and qualifiers at dots, hyphens and digit-letter boundaries; numbers compare as numbers; qualifiers rank
 * {@code alpha < beta < milestone < rc < snapshot < release < sp}, then anything unknown, alphabetically; and
 * trailing zeros and release qualifiers do not count, so {@code 1}, {@code 1.0} and {@code 1.0-ga} are equal. It
 * does not reproduce every corner of Maven's list-nesting rules, which matter only between exotic versions of
 * the same artifact.
 */
final class Versions {

    private Versions() {}

    static final Comparator<String> ORDER = Versions::compare;

    /** Whether {@code spec} is a range — {@code [1.0,2.0)}, {@code (,1.0]}, {@code [1.5]} — rather than a version. */
    static boolean isRange(String spec) {
        String s = spec.trim();
        return s.startsWith("[") || s.startsWith("(");
    }

    /**
     * The version {@code spec} names among {@code available}: itself when it is a plain version, and for a range
     * or {@code LATEST}/{@code RELEASE}, the highest available version it admits — or empty when none does.
     */
    static Optional<String> select(String spec, Collection<String> available) {
        String s = spec.trim();
        if (s.equals("LATEST") || s.equals("RELEASE")) {
            return available.stream()
                    .filter(v -> s.equals("LATEST") || !v.endsWith("-SNAPSHOT"))
                    .max(ORDER);
        }
        if (!isRange(s)) return Optional.of(s);
        List<Range> ranges = parse(s);
        if (ranges.isEmpty()) return Optional.empty();
        return available.stream().filter(v -> ranges.stream().anyMatch(r -> r.admits(v))).max(ORDER);
    }

    /** One bracketed set: a lower and upper bound, each inclusive or not, either possibly absent. */
    private record Range(String lower, boolean lowerInclusive, String upper, boolean upperInclusive) {
        boolean admits(String v) {
            if (lower != null) {
                int c = compare(v, lower);
                if (c < 0 || c == 0 && !lowerInclusive) return false;
            }
            if (upper != null) {
                int c = compare(v, upper);
                if (c > 0 || c == 0 && !upperInclusive) return false;
            }
            return true;
        }
    }

    /** {@code (,1.0],[1.2,)} as its sets. A malformed spec yields none, so it selects nothing. */
    private static List<Range> parse(String spec) {
        List<Range> out = new ArrayList<>();
        int i = 0;
        while (i < spec.length()) {
            char open = spec.charAt(i);
            if (open == ',' || Character.isWhitespace(open)) { i++; continue; }
            if (open != '[' && open != '(') return List.of();
            int close = indexOfAny(spec, i + 1);
            if (close < 0) return List.of();
            String inside = spec.substring(i + 1, close).trim();
            boolean lowerInclusive = open == '[';
            boolean upperInclusive = spec.charAt(close) == ']';
            int comma = inside.indexOf(',');
            if (comma < 0) {
                if (!lowerInclusive || !upperInclusive || inside.isEmpty()) return List.of();
                out.add(new Range(inside, true, inside, true));   // [1.5]: exactly
            } else {
                String lower = inside.substring(0, comma).trim();
                String upper = inside.substring(comma + 1).trim();
                out.add(new Range(lower.isEmpty() ? null : lower, lowerInclusive,
                        upper.isEmpty() ? null : upper, upperInclusive));
            }
            i = close + 1;
        }
        return out;
    }

    private static int indexOfAny(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            if (s.charAt(i) == ']' || s.charAt(i) == ')') return i;
        }
        return -1;
    }

    // --- ordering -------------------------------------------------------------------------------

    static int compare(String a, String b) {
        List<Object> x = items(a);
        List<Object> y = items(b);
        for (int i = 0; i < Math.max(x.size(), y.size()); i++) {
            Object p = i < x.size() ? x.get(i) : null;
            Object q = i < y.size() ? y.get(i) : null;
            int c = compareItems(p, q);
            if (c != 0) return c;
        }
        return 0;
    }

    /** Numbers as {@link Long}, qualifiers as lower-case strings, with trailing zeros and release markers gone. */
    private static List<Object> items(String version) {
        List<Object> out = new ArrayList<>();
        String v = version.toLowerCase(Locale.ROOT);
        int i = 0;
        while (i < v.length()) {
            char c = v.charAt(i);
            if (c == '.' || c == '-' || c == '_') { i++; continue; }
            int j = i;
            boolean digits = Character.isDigit(c);
            while (j < v.length() && v.charAt(j) != '.' && v.charAt(j) != '-' && v.charAt(j) != '_'
                    && Character.isDigit(v.charAt(j)) == digits) {
                j++;
            }
            String token = v.substring(i, j);
            if (digits) {
                out.add(token.length() > 18 ? Long.MAX_VALUE : Long.parseLong(token));
            } else {
                // A single letter directly before a number is shorthand: 1.0a1, 1.0b2, 1.0m3.
                boolean shorthand = token.length() == 1 && j < v.length() && Character.isDigit(v.charAt(j));
                out.add(shorthand ? switch (token) {
                    case "a" -> "alpha";
                    case "b" -> "beta";
                    case "m" -> "milestone";
                    default -> token;
                } : token);
            }
            i = j;
        }
        while (!out.isEmpty() && isNothing(out.getLast())) out.removeLast();
        return out;
    }

    private static boolean isNothing(Object item) {
        return item instanceof Long n ? n == 0 : rank((String) item) == RELEASE;
    }

    private static final int RELEASE = 5;

    private static int rank(String qualifier) {
        return switch (qualifier) {
            case "alpha" -> 0;
            case "beta" -> 1;
            case "milestone" -> 2;
            case "rc", "cr" -> 3;
            case "snapshot" -> 4;
            case "", "ga", "final", "release" -> RELEASE;
            case "sp" -> 6;
            default -> 7;
        };
    }

    /** Null is what a shorter version has where a longer one has more: zero, or a release. */
    private static int compareItems(Object p, Object q) {
        if (p == null && q == null) return 0;
        if (p == null) return -compareItems(q, null);
        if (p instanceof Long n) {
            if (q == null) return Long.compare(n, 0);
            if (q instanceof Long m) return Long.compare(n, m);
            return 1;   // 1.0.1 is after 1.0-anything
        }
        String s = (String) p;
        if (q == null) return Integer.compare(rank(s), RELEASE);
        if (q instanceof Long) return -1;
        String t = (String) q;
        int c = Integer.compare(rank(s), rank(t));
        return c != 0 || rank(s) != 7 ? c : s.compareTo(t);
    }
}
