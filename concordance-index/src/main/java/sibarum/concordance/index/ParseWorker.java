package sibarum.concordance.index;

import com.github.javaparser.JavaParser;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Where source is parsed: one thread with a deep stack, reused for every file of a synchronize, and a failure
 * that stays with the file that caused it.
 *
 * <p>The thread exists because a parser for a recursive grammar needs stack in proportion to nesting, and the
 * caller's thread — an editor's, a test's — has whatever stack it was given. The containment exists because a
 * file is input: a {@link StackOverflowError} from a file nested past even this stack, or an exception from the
 * parser on input it was not ready for, is that file's problem, reported as a string, and the next file is parsed
 * by a fresh parser as if nothing happened.
 *
 * <p>Out-of-memory is not contained: a file is held to {@link Limits#maxSourceBytes} before it gets here, so
 * running out of memory is about the machine, not the file.
 */
final class ParseWorker implements AutoCloseable {

    private final Limits limits;
    private final ExecutorService thread;
    private JavaParser parser;

    ParseWorker(Limits limits) {
        this.limits = limits;
        this.thread = Executors.newSingleThreadExecutor(
                task -> new Thread(null, task, "concordance-parse", limits.parseStackBytes()));
    }

    /**
     * Indexes one source's bytes into {@code symbols} and {@code references}: everything, or only what it
     * exports when {@code exportsOnly}. Returns why it could not, or null when it could.
     */
    String collect(Source source, byte[] bytes, boolean exportsOnly, Set<String> exportedPackages,
                   List<Symbol> symbols, List<Reference> references) {
        return contained(() -> {
            if (exportsOnly) {
                IndexBuilder.collectExports(parser(), source, bytes, exportedPackages, symbols);
            } else {
                IndexBuilder.collectBytes(parser(), source, bytes, symbols, references);
            }
            return null;
        }, symbols, references);
    }

    /** The packages a {@code module-info.java} exports to everyone; null if it is not one, or cannot be read. */
    Set<String> exportsOf(byte[] moduleInfo) {
        Object[] found = new Object[1];
        String problem = contained(() -> {
            found[0] = IndexBuilder.exportsOf(parser(), moduleInfo);
            return null;
        }, List.of(), List.of());
        @SuppressWarnings("unchecked")
        Set<String> exports = problem == null ? (Set<String>) found[0] : null;
        return exports;
    }

    @Override
    public void close() {
        thread.shutdownNow();
    }

    // ------------------------------------------------------------------------------------------

    private interface Work {
        String run();
    }

    /** Runs {@code work} on the parse thread; on failure, empties what it half-filled and says what happened. */
    private String contained(Work work, List<Symbol> symbols, List<Reference> references) {
        Future<String> outcome = thread.submit(() -> {
            try {
                return work.run();
            } catch (StackOverflowError deep) {
                parser = null;   // a parser abandoned mid-parse is not trusted with the next file
                return "nested too deeply to parse within a " + (limits.parseStackBytes() >> 20)
                        + " MB stack";
            } catch (RuntimeException broken) {
                parser = null;
                return "the parser failed on it: " + broken;
            }
        });
        String problem;
        try {
            problem = outcome.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome.cancel(true);
            problem = "interrupted while parsing";
        } catch (ExecutionException e) {
            // Only an Error other than stack overflow gets here; that is not the file's doing, so it is not hidden.
            if (e.getCause() instanceof Error error) throw error;
            throw new IllegalStateException(e.getCause());
        }
        if (problem != null) {
            clearQuietly(symbols);
            clearQuietly(references);
        }
        return problem;
    }

    private static void clearQuietly(List<?> list) {
        try {
            list.clear();
        } catch (UnsupportedOperationException immutable) {
            // List.of() passed by a caller that wants nothing back anyway
        }
    }

    private JavaParser parser() {
        if (parser == null) parser = IndexBuilder.parser();
        return parser;
    }

    /**
     * Up to {@code limit} bytes of {@code in}, or null when there are more — counted on what is actually read, so a
     * zip entry that understates its size in its header is caught all the same.
     */
    static byte[] readBounded(InputStream in, long limit) throws IOException {
        byte[] bytes = in.readNBytes((int) Math.min(limit + 1, Integer.MAX_VALUE - 8));
        return bytes.length > limit ? null : bytes;
    }
}
