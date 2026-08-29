package sibarum.concordance.index;

import java.nio.file.Path;

/**
 * A place in the source: file, line, column. One-based, as an editor counts.
 *
 * <p>This is what makes an index answer useful rather than merely correct — every result has to be somewhere a
 * caret can go.
 */
public record SourceRef(Path file, int line, int column) {

    public SourceRef {
        if (file == null) {
            throw new IllegalArgumentException("SourceRef file must be non-null");
        }
    }

    /** The form an editor and a shell both read: {@code path:line}. */
    @Override
    public String toString() {
        return file + ":" + line;
    }
}
