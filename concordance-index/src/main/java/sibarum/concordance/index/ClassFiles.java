package sibarum.concordance.index;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.ModuleExportInfo;
import java.lang.classfile.attribute.RecordComponentInfo;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.constant.ClassDesc;
import java.lang.reflect.AccessFlag;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * What a jar without sources exports, read from its class files.
 *
 * <p>The declarations a project can name are all in a class file, exactly — names, access, supertypes, member
 * signatures — so they are read from there, not from decompiled text. That is fast, it is right even for a
 * class the decompiler mangles, and it keeps the names obfuscators choose: names that are not valid Java, which a
 * decompiled text would either rename or fail to parse on, are indexed as the bytecode spells them.
 *
 * <p>The rules are those of {@link IndexBuilder#collectExports}, as the class file states them: a public
 * top-level class, or a public or protected member class of an exported one, in a package {@code module-info}
 * exports; its public and protected methods, constructors and fields; an enum's constants; a record's components.
 * What the compiler made up — synthetic and bridge methods, the outer-instance parameter of an inner class's
 * constructor, a record's accessors — is left out, so a class indexed from bytecode says what the same class
 * indexed from its source says. Parameter types are written as source writes them: simple names, erased, and a
 * varargs parameter without its brackets.
 *
 * <p>A class's declarations belong to its outermost class, because that is what is decompiled for display (see
 * {@link Decompilation}): {@code a/b/Outer$Inner.class} is shown inside {@code a/b/Outer.class}. Their positions are
 * not known until the text exists, so they start at line 0 — "not yet placed" — and {@link Source#locate} places
 * them on demand.
 */
final class ClassFiles {

    /** What this reader extracts. Bump it whenever it would record anything differently. */
    static final int VERSION = 1;

    /** One class file, reduced to what indexing needs; nothing of it refers back to the bytes. */
    record Read(String entry, String internalName, Symbol.Kind kind, int flags, Optional<Nested> nested,
                List<String> supers, List<Member> members) {}

    /** How a class sits inside another, from its own {@code InnerClasses} entry. */
    record Nested(Optional<String> outer, Optional<String> simpleName, int flags) {}

    record Member(Symbol.Kind kind, String name, List<String> parameters) {}

    private ClassFiles() {}

    /** One class file's bytes, read; throws {@link IllegalArgumentException} for bytes that are not one. */
    static Read read(String entry, byte[] bytes) {
        ClassModel model = ClassFile.of().parse(bytes);
        String internalName = model.thisClass().asInternalName();
        int flags = model.flags().flagsMask();

        Optional<Nested> nested = Optional.empty();
        var inner = model.findAttribute(Attributes.innerClasses());
        if (inner.isPresent()) {
            for (InnerClassInfo info : inner.get().classes()) {
                if (info.innerClass().asInternalName().equals(internalName)) {
                    nested = Optional.of(new Nested(info.outerClass().map(ClassEntry::asInternalName),
                            info.innerName().map(n -> n.stringValue()), info.flagsMask()));
                }
            }
        }

        boolean isRecord = model.findAttribute(Attributes.record()).isPresent();
        Symbol.Kind kind = has(flags, AccessFlag.ANNOTATION) ? Symbol.Kind.ANNOTATION
                : has(flags, AccessFlag.INTERFACE) ? Symbol.Kind.INTERFACE
                : has(flags, AccessFlag.ENUM) ? Symbol.Kind.ENUM
                : isRecord ? Symbol.Kind.RECORD
                : Symbol.Kind.CLASS;

        List<String> supers = new ArrayList<>();
        model.superclass().map(ClassEntry::asInternalName)
                .filter(s -> !IMPLICIT_SUPERS.contains(s))
                .ifPresent(s -> supers.add(dotted(s)));
        model.interfaces().stream().map(ClassEntry::asInternalName)
                .filter(s -> !IMPLICIT_SUPERS.contains(s))
                .forEach(s -> supers.add(dotted(s)));

        List<Member> members = new ArrayList<>();
        for (FieldModel field : model.fields()) {
            int f = field.flags().flagsMask();
            if (has(f, AccessFlag.ENUM)) members.add(new Member(Symbol.Kind.ENUM_CONSTANT, field.fieldName().stringValue(), null));
        }
        Set<String> components = new HashSet<>();
        model.findAttribute(Attributes.record()).ifPresent(r -> {
            for (RecordComponentInfo component : r.components()) {
                components.add(component.name().stringValue());
                members.add(new Member(Symbol.Kind.FIELD, component.name().stringValue(), null));
            }
        });
        boolean innerInstance = nested.isPresent() && !has(nested.get().flags(), AccessFlag.STATIC)
                && kind == Symbol.Kind.CLASS;
        for (MethodModel method : model.methods()) {
            int m = method.flags().flagsMask();
            String name = method.methodName().stringValue();
            if (!visible(m) || has(m, AccessFlag.SYNTHETIC) || has(m, AccessFlag.BRIDGE) || name.equals("<clinit>")) {
                continue;
            }
            List<String> parameters = new ArrayList<>();
            for (ClassDesc parameter : method.methodTypeSymbol().parameterList()) {
                parameters.add(simple(parameter.displayName()));
            }
            if (name.equals("<init>")) {
                if (kind == Symbol.Kind.ENUM) continue;   // an enum's constructors are never callable from outside
                if (innerInstance && !parameters.isEmpty()) parameters.removeFirst();   // the outer instance
            } else if (parameters.isEmpty() && components.contains(name)) {
                continue;   // a record accessor; the component is already indexed as the field it is
            }
            if (has(m, AccessFlag.VARARGS) && !parameters.isEmpty()) {
                String last = parameters.removeLast();
                parameters.add(last.endsWith("[]") ? last.substring(0, last.length() - 2) : last);
            }
            members.add(new Member(name.equals("<init>") ? Symbol.Kind.CONSTRUCTOR : Symbol.Kind.METHOD,
                    name, parameters));
        }
        for (FieldModel field : model.fields()) {
            int f = field.flags().flagsMask();
            if (visible(f) && !has(f, AccessFlag.SYNTHETIC) && !has(f, AccessFlag.ENUM)) {
                members.add(new Member(Symbol.Kind.FIELD, field.fieldName().stringValue(), null));
            }
        }
        return new Read(entry, internalName, kind, flags, nested, supers, members);
    }

    /** The packages a {@code module-info.class} exports to everyone, dotted; null if it is not one. */
    static Set<String> exportsOf(byte[] moduleInfo) {
        ClassModel model = ClassFile.of().parse(moduleInfo);
        return model.findAttribute(Attributes.module()).map(module -> {
            Set<String> out = new LinkedHashSet<>();
            for (ModuleExportInfo export : module.exports()) {
                if (export.exportsTo().isEmpty()) out.add(export.exportedPackage().name().stringValue().replace('/', '.'));
            }
            return (Set<String>) out;
        }).orElse(null);
    }

    /**
     * The exported declarations of a jar's classes, by the outermost class entry each belongs to, groups in entry
     * order. A class whose outer class is missing from the jar is treated as outermost itself.
     */
    static Map<String, List<Symbol>> symbols(Path jar, List<Read> classes, Set<String> exportedPackages) {
        Map<String, Read> byName = new HashMap<>();
        classes.forEach(c -> byName.put(c.internalName(), c));
        Map<String, Boolean> exported = new HashMap<>();
        Map<String, List<Symbol>> groups = new TreeMap<>();
        for (Read c : classes) {
            if (!isExported(c, byName, exportedPackages, exported, 0)) continue;
            String outermost = outermost(c, byName);
            Source source = new Source.Decompiled(jar, byName.get(outermost).entry());
            List<Symbol> group = groups.computeIfAbsent(byName.get(outermost).entry(), k -> new ArrayList<>());
            String qualified = qualified(c, byName, 0);
            String owner = c.nested().flatMap(Nested::outer).filter(byName::containsKey)
                    .map(o -> qualified(byName.get(o), byName, 0)).orElse(packageOf(c.internalName()));
            SourceRef unplaced = new SourceRef(source, 0, 0);
            group.add(new Symbol(c.kind(), simpleNameOf(c), owner, qualified, unplaced, c.supers()));
            for (Member m : c.members()) {
                String memberName = m.kind() == Symbol.Kind.CONSTRUCTOR ? simpleNameOf(c) : m.name();
                String id = m.kind() == Symbol.Kind.CONSTRUCTOR ? "<init>" : m.name();
                String signature = m.parameters() == null ? "" : "(" + String.join(",", m.parameters()) + ")";
                group.add(new Symbol(m.kind(), memberName, qualified, qualified + "#" + id + signature, unplaced,
                        List.of()));
            }
        }
        return groups;
    }

    /**
     * {@code a.b.C#m(java.util.List<String>, int...)} as {@code a.b.C#m(List,int)}: generics gone and each
     * parameter type by its simple name, which is what a class file and a decompiled text can both be brought to.
     */
    static String normalized(String qualified) {
        int open = qualified.indexOf('(');
        if (open < 0) return qualified;
        String inside = qualified.substring(open + 1, qualified.length() - 1);
        StringBuilder bare = new StringBuilder();
        int depth = 0;
        for (char c : inside.toCharArray()) {
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (depth == 0 && !Character.isWhitespace(c)) bare.append(c);
        }
        List<String> types = new ArrayList<>();
        for (String type : bare.toString().split(",")) {
            if (type.isEmpty()) continue;
            String simple = type.substring(type.lastIndexOf('.') + 1);
            types.add(simple.endsWith("...") ? simple.substring(0, simple.length() - 3) : simple);
        }
        return qualified.substring(0, open) + "(" + String.join(",", types) + ")";
    }

    // ------------------------------------------------------------------------------------------

    private static final Set<String> IMPLICIT_SUPERS = Set.of(
            "java/lang/Object", "java/lang/Enum", "java/lang/Record", "java/lang/annotation/Annotation");

    private static final int MAX_NESTING = 64;   // a cycle of outer classes, which only a crafted jar has

    private static boolean isExported(Read c, Map<String, Read> byName, Set<String> exportedPackages,
                                      Map<String, Boolean> memo, int depth) {
        Boolean known = memo.get(c.internalName());
        if (known != null) return known;
        boolean result;
        if (depth > MAX_NESTING) {
            result = false;
        } else if (c.nested().isEmpty()) {
            result = has(c.flags(), AccessFlag.PUBLIC)
                    && (exportedPackages == null || exportedPackages.contains(packageOf(c.internalName())));
        } else {
            Nested n = c.nested().get();
            // No outer class or no name: a local or anonymous class, which nobody outside can name.
            result = n.outer().isPresent() && n.simpleName().isPresent() && visible(n.flags())
                    && byName.containsKey(n.outer().get())
                    && isExported(byName.get(n.outer().get()), byName, exportedPackages, memo, depth + 1);
        }
        memo.put(c.internalName(), result);
        return result;
    }

    private static String outermost(Read c, Map<String, Read> byName) {
        Read current = c;
        for (int depth = 0; depth < MAX_NESTING; depth++) {
            Optional<String> outer = current.nested().flatMap(Nested::outer).filter(byName::containsKey);
            if (outer.isEmpty()) break;
            current = byName.get(outer.get());
        }
        return current.internalName();
    }

    private static String qualified(Read c, Map<String, Read> byName, int depth) {
        Optional<String> outer = c.nested().flatMap(Nested::outer).filter(byName::containsKey);
        if (outer.isEmpty() || depth > MAX_NESTING) {
            String pkg = packageOf(c.internalName());
            return pkg.isEmpty() ? simpleNameOf(c) : pkg + "." + simpleNameOf(c);
        }
        return qualified(byName.get(outer.get()), byName, depth + 1) + "." + simpleNameOf(c);
    }

    private static String simpleNameOf(Read c) {
        return c.nested().flatMap(Nested::simpleName).orElseGet(() -> {
            String name = c.internalName();
            return name.substring(name.lastIndexOf('/') + 1);
        });
    }

    private static String packageOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash).replace('/', '.');
    }

    /** {@code a/b/Outer$Inner} as source names it: {@code a.b.Outer.Inner}. */
    private static String dotted(String internalName) {
        return internalName.replace('/', '.').replace('$', '.');
    }

    /** A descriptor's display name as source writes a parameter type: {@code Outer$Inner[]} is {@code Inner[]}. */
    private static String simple(String displayName) {
        return displayName.substring(displayName.lastIndexOf('$') + 1);
    }

    private static boolean visible(int flags) {
        return has(flags, AccessFlag.PUBLIC) || has(flags, AccessFlag.PROTECTED);
    }

    private static boolean has(int flags, AccessFlag flag) {
        return (flags & flag.mask()) != 0;
    }
}
