package sibarum.concordance.index;

/**
 * A place in the source: which source, line, column. One-based, as an editor counts.
 *
 * <p>This is what makes an index answer useful rather than merely correct — every result has to be somewhere a
 * caret can go. The source is usually a file, but may be an entry in a library's sources jar, or a library class
 * shown decompiled; see {@link Source}. A declaration read from a class file is at line 0 until it is placed:
 * {@link Source#locate} finds it in the decompiled text.
 */
public record SourceRef(Source source, int line, int column) {

    public SourceRef {
        if (source == null) {
            throw new IllegalArgumentException("SourceRef source must be non-null");
        }
    }

    /** The form an editor and a shell both read: {@code path:line}, or {@code archive!/entry:line}. */
    @Override
    public String toString() {
        return source + ":" + line;
    }
}
