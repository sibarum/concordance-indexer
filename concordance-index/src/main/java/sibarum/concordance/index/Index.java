package sibarum.concordance.index;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Everything declared in a project and every place a name is used, with the queries an editor asks.
 *
 * <p>Results come back in a stable order — file, then line — so two runs over unchanged source agree, and a
 * caller can page through them.
 *
 * <p>See {@link Reference} on why matching is by name: this index over-approximates usages rather than dropping
 * the ones it cannot resolve, because an index that silently omits a usage is worse than one that offers an
 * extra.
 */
public record Index(List<Symbol> symbols, List<Reference> references) {

    private static final Comparator<SourceRef> BY_POSITION =
            Comparator.comparing((SourceRef r) -> r.file().toString())
                    .thenComparingInt(SourceRef::line)
                    .thenComparingInt(SourceRef::column);

    public Index {
        symbols = List.copyOf(symbols);
        references = List.copyOf(references);
    }

    // --- find names with a substring ----------------------------------------------------------

    /**
     * Declarations whose simple name contains {@code fragment}, case-insensitively.
     *
     * <p>Exact matches come first, then names that start with the fragment, then the rest — so typing a whole
     * name does not bury it under everything that merely contains it.
     */
    public List<Symbol> namesContaining(String fragment) {
        String needle = fragment.toLowerCase(Locale.ROOT);
        return symbols.stream()
                .filter(s -> s.name().toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator
                        .comparingInt((Symbol s) -> rank(s.name(), fragment))
                        .thenComparing(Symbol::name)
                        .thenComparing(Symbol::at, BY_POSITION))
                .toList();
    }

    private static int rank(String name, String fragment) {
        if (name.equalsIgnoreCase(fragment)) return 0;
        if (name.toLowerCase(Locale.ROOT).startsWith(fragment.toLowerCase(Locale.ROOT))) return 1;
        return 2;
    }

    // --- find usages --------------------------------------------------------------------------

    /** Every use of the simple name {@code name}, in source order. */
    public List<Reference> usagesOf(String name) {
        return references.stream()
                .filter(r -> r.name().equals(name))
                .sorted(Comparator.comparing(Reference::at, BY_POSITION))
                .toList();
    }

    /**
     * Every use of a declaration.
     *
     * <p>For a method, uses are calls of that name with that many arguments — arity narrows the
     * over-approximation without needing a resolver. For a type, uses are the places the type is named, which
     * includes {@code extends} and {@code implements}. The declaration itself is not a use of itself.
     */
    public List<Reference> usagesOf(Symbol symbol) {
        List<Reference> matches = switch (symbol.kind()) {
            case METHOD, CONSTRUCTOR -> {
                int arity = arityOf(symbol.qualified());
                yield references.stream()
                        .filter(r -> r.kind() == Reference.Kind.CALL || r.kind() == Reference.Kind.CONSTRUCT)
                        .filter(r -> r.name().equals(symbol.name()))
                        .filter(r -> arity < 0 || r.target().equals(Reference.callTarget(symbol.name(), arity)))
                        .toList();
            }
            default -> references.stream().filter(r -> r.name().equals(symbol.name())).toList();
        };
        return matches.stream()
                .filter(r -> !isTheDeclarationItself(r, symbol))
                .sorted(Comparator.comparing(Reference::at, BY_POSITION))
                .toList();
    }

    private static boolean isTheDeclarationItself(Reference reference, Symbol symbol) {
        return reference.at().file().equals(symbol.at().file())
                && reference.at().line() == symbol.at().line();
    }

    /** The arity encoded in a member's qualified name, or -1 when there is none. */
    private static int arityOf(String qualified) {
        int open = qualified.indexOf('(');
        if (open < 0 || !qualified.endsWith(")")) return -1;
        String inside = qualified.substring(open + 1, qualified.length() - 1).trim();
        if (inside.isEmpty()) return 0;
        int depth = 0, count = 1;
        for (char c : inside.toCharArray()) {
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) count++;
        }
        return count;
    }

    // --- find implementations -----------------------------------------------------------------

    /**
     * Types that extend or implement {@code typeName}, transitively, plus — when the name is a method — the
     * methods of that name declared in them.
     *
     * <p>Transitive because "find implementations" means the leaves, not just the direct children: an interface
     * with an abstract base class between it and the real implementations would otherwise return the abstract
     * class alone, which is never the answer anyone wanted.
     */
    public List<Symbol> implementationsOf(String typeName) {
        Map<String, List<Symbol>> bySuper = new LinkedHashMap<>();
        for (Symbol symbol : symbols) {
            if (!symbol.kind().isType()) continue;
            for (String parent : symbol.supers()) {
                bySuper.computeIfAbsent(simpleName(parent), k -> new ArrayList<>()).add(symbol);
            }
        }

        Set<Symbol> found = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        Set<String> seen = new LinkedHashSet<>();
        pending.add(simpleName(typeName));
        while (!pending.isEmpty()) {
            String current = pending.poll();
            if (!seen.add(current)) continue;
            for (Symbol child : bySuper.getOrDefault(current, List.of())) {
                if (found.add(child)) {
                    pending.add(child.name());
                }
            }
        }
        return found.stream()
                .sorted(Comparator.comparing(Symbol::name).thenComparing(Symbol::at, BY_POSITION))
                .toList();
    }

    /**
     * Methods that implement or override {@code methodName} — the declarations of that name in every type below
     * the one declaring it.
     */
    public List<Symbol> implementationsOfMethod(String ownerTypeName, String methodName) {
        Set<String> implementors = new LinkedHashSet<>();
        implementationsOf(ownerTypeName).forEach(s -> implementors.add(s.qualified()));
        return symbols.stream()
                .filter(s -> s.kind() == Symbol.Kind.METHOD)
                .filter(s -> s.name().equals(methodName))
                .filter(s -> implementors.contains(s.owner()))
                .sorted(Comparator.comparing(Symbol::owner).thenComparing(Symbol::at, BY_POSITION))
                .toList();
    }

    private static String simpleName(String typeName) {
        String withoutArgs = typeName.contains("<")
                ? typeName.substring(0, typeName.indexOf('<')) : typeName;
        int dot = withoutArgs.lastIndexOf('.');
        return dot < 0 ? withoutArgs : withoutArgs.substring(dot + 1);
    }

    // --- odds and ends ------------------------------------------------------------------------

    /** Declarations in one file, in source order — what an outline view wants. */
    public List<Symbol> declaredIn(Path file) {
        return symbols.stream()
                .filter(s -> s.at().file().equals(file))
                .sorted(Comparator.comparing(Symbol::at, BY_POSITION))
                .toList();
    }

    public List<Symbol> types() {
        return symbols.stream().filter(s -> s.kind().isType()).toList();
    }

    public String summary() {
        long types = symbols.stream().filter(s -> s.kind().isType()).count();
        long methods = symbols.stream().filter(s -> s.kind() == Symbol.Kind.METHOD).count();
        long fields = symbols.stream().filter(s -> s.kind() == Symbol.Kind.FIELD).count();
        return types + " types, " + methods + " methods, " + fields + " fields, "
                + references.size() + " references";
    }
}
