package sibarum.concordance.index;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which library sources a project file links to: those declaring a type it imports, or names through an import,
 * a wildcard import, or its fully qualified name. Imports are the one place a file says exactly which type it
 * means, so this is the reference graph read where it is precise: a file that merely uses a simple name another
 * library also happens to declare does not link to that library unless an import says so.
 *
 * <p>Shared by {@link Plan}, which searches linked libraries before the rest, and {@link EffectiveDependencies},
 * which tells a project which of its libraries it links to at all.
 */
final class Links {

    private Links() {}

    /**
     * Which of {@code libraries} declares each type, by qualified name; when two declare one, the earlier wins, so
     * hand them over nearest first.
     */
    static Map<String, Source> declaredBy(Corpus corpus, List<Source> libraries) {
        Map<String, Source> declaredBy = new HashMap<>();
        for (Source s : libraries) {
            corpus.slice(s).ifPresent(slice -> slice.symbols().stream()
                    .filter(sym -> sym.kind().isType())
                    .forEach(sym -> declaredBy.putIfAbsent(sym.qualified(), s)));
        }
        return declaredBy;
    }

    /**
     * Adds to {@code out}, in the order the file reaches them, the library sources one project file links to:
     * through its imports, and through type names it writes, read against those imports.
     */
    static void of(Corpus.Slice slice, Map<String, Source> declaredBy, Set<Source> out) {
        Map<String, String> single = new LinkedHashMap<>();   // simple name -> what it was imported as
        List<String> wildcards = new ArrayList<>();
        for (Reference r : slice.references()) {
            if (r.kind() != Reference.Kind.IMPORT) continue;
            String target = r.target();
            if (target.endsWith(".*")) {
                String scope = target.substring(0, target.length() - 2);
                wildcards.add(scope);
                link(scope, declaredBy, out);   // import static a.b.C.* links to C
            } else {
                single.put(r.name(), target);
                // import a.b.C, or import static a.b.C.m: the type is the target or the target's owner.
                if (!link(target, declaredBy, out)) {
                    int dot = target.lastIndexOf('.');
                    if (dot > 0) link(target.substring(0, dot), declaredBy, out);
                }
            }
        }
        for (Reference r : slice.references()) {
            String written = switch (r.kind()) {
                case TYPE_USE, EXTENDS, IMPLEMENTS -> r.target();
                case CONSTRUCT -> r.name();
                default -> null;
            };
            if (written == null) continue;
            if (link(written, declaredBy, out)) continue;   // written fully qualified
            int dot = written.indexOf('.');
            String first = dot < 0 ? written : written.substring(0, dot);
            String rest = dot < 0 ? "" : written.substring(dot);
            String imported = single.get(first);
            if (imported != null && link(imported + rest, declaredBy, out)) continue;
            for (String scope : wildcards) {
                if (link(scope + "." + written, declaredBy, out)) break;
            }
        }
    }

    private static boolean link(String qualified, Map<String, Source> declaredBy, Set<Source> out) {
        Source s = declaredBy.get(qualified);
        if (s == null) return false;
        out.add(s);
        return true;
    }
}
