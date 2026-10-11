package sibarum.concordance.index;

import java.util.List;

/**
 * Something declared in the source, and where it was declared.
 *
 * @param kind      what sort of declaration it is
 * @param name      the simple name, as written
 * @param owner     the fully-qualified type it is declared in; for a type, its own enclosing type or the package
 * @param qualified the fully-qualified name — {@code a.b.C}, or {@code a.b.C#m(int)} for a member
 * @param at        the source and line it is declared on
 * @param supers    for a type, the names of the types it extends and implements, as written
 */
public record Symbol(
        Kind kind,
        String name,
        String owner,
        String qualified,
        SourceRef at,
        List<String> supers) {

    public enum Kind {
        CLASS, INTERFACE, ENUM, RECORD, ANNOTATION, METHOD, CONSTRUCTOR, FIELD, ENUM_CONSTANT;

        /** Whether this kind declares a type, as opposed to a member of one. */
        public boolean isType() {
            return this == CLASS || this == INTERFACE || this == ENUM
                    || this == RECORD || this == ANNOTATION;
        }
    }

    public Symbol {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Symbol name must be non-empty");
        }
        supers = supers == null ? List.of() : List.copyOf(supers);
    }

    public Source source() {
        return at.source();
    }

    public int line() {
        return at.line();
    }
}
