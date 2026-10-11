package sibarum.concordance.index;

/**
 * How much an index will spend on any one input before it gives up on that input — and only that input.
 *
 * <p>A project's own source is trusted to be source; a jar from a repository is not trusted to be anything. A
 * sources jar can hold a zip bomb, a file nested deeper than a parser's stack, or a hundred thousand entries, by
 * accident or by design, and none of that should cost the rest of the index. So each input has a budget, and an
 * input over budget is indexed as empty, with a problem recorded that says why (see {@link ProjectIndex#problems}).
 *
 * @param maxSourceBytes        the largest single source file read, on disk or in a jar. Measured on the bytes
 *                              actually read, not on what a zip entry's header claims.
 * @param maxArchiveSourceBytes the most source read out of one jar, all entries together
 * @param maxArchiveEntries     the most {@code .java} entries indexed from one jar
 * @param parseStackBytes       the stack of the thread that parses. Java's grammar is recursive, so nesting
 *                              depth costs stack: a default thread stack overflows at a few hundred nested
 *                              parentheses, and a file nested deeper than this stack allows is a problem, not a
 *                              crash.
 * @param decompileMillis       how long decompiling one class for display may take. Obfuscated control flow can
 *                              send a decompiler round in circles; past this, the class is not shown, rather than
 *                              the caller kept waiting.
 */
public record Limits(long maxSourceBytes, long maxArchiveSourceBytes, int maxArchiveEntries, long parseStackBytes,
                     long decompileMillis) {

    /** Generous for real code — the largest generated sources run to a few megabytes — and finite for the rest. */
    public static final Limits DEFAULT = new Limits(16L << 20, 1L << 30, 200_000, 256L << 20, 30_000);

    public Limits {
        if (maxSourceBytes <= 0 || maxArchiveSourceBytes <= 0 || maxArchiveEntries <= 0 || parseStackBytes <= 0
                || decompileMillis <= 0) {
            throw new IllegalArgumentException("every limit must be positive: " + this);
        }
    }
}
