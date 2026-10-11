package sibarum.concordance.index;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.modules.ModuleDirective;
import com.github.javaparser.ast.modules.ModuleExportsDirective;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import sibarum.concordance.project.MavenProject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Builds an {@link Index} by parsing every source file of a project.
 *
 * <p>Parses at {@link ParserConfiguration.LanguageLevel#RAW}: no validators run, so nothing reflects and the
 * newest syntax still parses. See {@code docs/javaparser-evaluation.md}.
 *
 * <p>Names are syntactic. A type written {@code List<String>} is recorded as a use of {@code List}, and a call
 * {@code close()} as a use of {@code close}, without asking what either resolves to. That makes the index an
 * over-approximation of usage and an exact record of declaration.
 *
 * <p>A library's source is read the same way and kept to what it exports; see {@link #collectExports}.
 */
public final class IndexBuilder {

    /**
     * What this builder extracts, as a version. Bump it whenever {@link #collect} would record anything
     * differently — a new reference kind, a changed qualified-name scheme, a JavaParser upgrade that parses
     * something differently — because an index saved by an older builder is then not one this builder would
     * produce, and {@link ProjectIndex#load} must refuse it rather than mix the two.
     */
    public static final int VERSION = 3;

    private IndexBuilder() {}

    static JavaParser parser() {
        return new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.RAW));
    }

    /** Indexes every main source file of every module, in reactor order. */
    public static Index build(MavenProject project) throws IOException {
        return buildFiles(project.javaFiles());
    }

    public static Index buildFiles(List<Path> files) throws IOException {
        List<Symbol> symbols = new ArrayList<>();
        List<Reference> references = new ArrayList<>();
        try (ParseWorker worker = new ParseWorker(Limits.DEFAULT)) {
            for (Path file : files) {
                List<Symbol> fileSymbols = new ArrayList<>();
                List<Reference> fileReferences = new ArrayList<>();
                worker.collect(new Source.OnDisk(file), Files.readAllBytes(file), false, null,
                        fileSymbols, fileReferences);
                symbols.addAll(fileSymbols);
                references.addAll(fileReferences);
            }
        }
        return new Index(symbols, references);
    }

    /** Indexes source text directly — what tests and the editor's unsaved buffers use. */
    public static Index buildSource(Path attributedTo, String source) {
        List<Symbol> symbols = new ArrayList<>();
        List<Reference> references = new ArrayList<>();
        try (ParseWorker worker = new ParseWorker(Limits.DEFAULT)) {
            worker.collect(new Source.OnDisk(attributedTo), source.getBytes(StandardCharsets.UTF_8), false, null,
                    symbols, references);
        }
        return new Index(symbols, references);
    }

    /**
     * Indexes one file's bytes, decoded as {@link #buildFiles} decodes the file itself — so a file indexed from
     * the bytes already read to hash it is indistinguishable from one indexed from disk.
     */
    static void collectBytes(JavaParser parser, Source source, byte[] bytes,
                             List<Symbol> symbols, List<Reference> references) {
        parser.parse(new ByteArrayInputStream(bytes)).getResult()
                .ifPresent(cu -> collect(cu, source, false, symbols, references));
    }

    /**
     * Indexes one library source's bytes for what it exports, and nothing else: the types and members code
     * outside the library can name, so a project can go to their definitions and ask what an imported type
     * offers. Nothing private, package-private or local is recorded, and no references at all — what a library
     * calls inside itself is not a question anyone indexing a project is asking.
     *
     * <p>Exported means public, or protected — a subclass in the project can name a protected member — on a
     * type that is itself exported all the way out, in a package the library exports. Interface and annotation
     * members are public unless declared private; an enum's constructors never are.
     *
     * @param exportedPackages the packages the library's {@code module-info} exports to everyone, or
     *                         {@code null} when it has none and so exports every package
     */
    static void collectExports(JavaParser parser, Source source, byte[] bytes, Set<String> exportedPackages,
                               List<Symbol> symbols) {
        parser.parse(new ByteArrayInputStream(bytes)).getResult()
                .filter(cu -> exportedPackages == null || exportedPackages.contains(packageOf(cu)))
                .ifPresent(cu -> collect(cu, source, true, symbols, new ArrayList<>()));
    }

    /**
     * The packages a {@code module-info.java} exports to every module, or {@code null} when the source is not a
     * module declaration. A qualified export ({@code exports p to m}) is to named friends, not to the project
     * being indexed, so it does not count.
     */
    static Set<String> exportsOf(JavaParser parser, byte[] moduleInfo) {
        return parser.parse(new ByteArrayInputStream(moduleInfo)).getResult()
                .flatMap(CompilationUnit::getModule)
                .map(module -> {
                    Set<String> out = new LinkedHashSet<>();
                    for (ModuleDirective directive : module.getDirectives()) {
                        if (directive instanceof ModuleExportsDirective exports
                                && exports.getModuleNames().isEmpty()) {
                            out.add(exports.getNameAsString());
                        }
                    }
                    return (Set<String>) out;
                })
                .orElse(null);
    }

    // ------------------------------------------------------------------------------------------

    private static void collect(CompilationUnit cu, Source file, boolean exportsOnly,
                                List<Symbol> symbols, List<Reference> references) {
        String pkg = packageOf(cu);

        for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
            if (exportsOnly && !exported(type)) continue;
            String qualified = qualifiedTypeName(type, pkg);
            List<String> supers = supersOf(type);
            symbols.add(new Symbol(kindOf(type), type.getNameAsString(),
                    enclosing(type, pkg), qualified, at(file, type), supers));
            if (exportsOnly) {
                collectMembers(type, qualified, file, true, symbols);
                continue;
            }

            // extends / implements are references to the named types, and are how implementations are found.
            if (type instanceof ClassOrInterfaceDeclaration decl) {
                for (ClassOrInterfaceType parent : decl.getExtendedTypes()) {
                    references.add(new Reference(Reference.Kind.EXTENDS, parent.getNameWithScope(),
                            parent.getNameAsString(), qualified, at(file, parent)));
                }
                for (ClassOrInterfaceType parent : decl.getImplementedTypes()) {
                    references.add(new Reference(Reference.Kind.IMPLEMENTS, parent.getNameWithScope(),
                            parent.getNameAsString(), qualified, at(file, parent)));
                }
            }

            collectMembers(type, qualified, file, false, symbols);
        }

        if (exportsOnly) return;

        // Imports say exactly which type a simple name in this file means; nothing else in a file is qualified.
        for (ImportDeclaration imported : cu.getImports()) {
            String target = imported.getNameAsString() + (imported.isAsterisk() ? ".*" : "");
            String name = imported.isAsterisk() ? "*" : imported.getName().getIdentifier();
            references.add(new Reference(Reference.Kind.IMPORT, target, name, pkg, at(file, imported)));
        }
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            references.add(new Reference(Reference.Kind.CALL,
                    Reference.callTarget(call.getNameAsString(), call.getArguments().size()),
                    call.getNameAsString(), enclosingDeclaration(call, pkg), at(file, call)));
        }
        for (ObjectCreationExpr created : cu.findAll(ObjectCreationExpr.class)) {
            String name = created.getType().getNameAsString();
            references.add(new Reference(Reference.Kind.CONSTRUCT,
                    Reference.callTarget(name, created.getArguments().size()),
                    name, enclosingDeclaration(created, pkg), at(file, created)));
        }
        for (ClassOrInterfaceType used : cu.findAll(ClassOrInterfaceType.class)) {
            // The scope half of a qualified name is a package, not a type; skip it as the drafter does.
            boolean isScopeOfAnother = used.getParentNode()
                    .filter(p -> p instanceof ClassOrInterfaceType c
                            && c.getScope().map(s -> s == used).orElse(false))
                    .isPresent();
            if (isScopeOfAnother) continue;
            // `new Circle(2)` is one use at one place. Its type node would otherwise be recorded a second time,
            // and the reader would be shown the same line twice with nothing to distinguish the entries.
            boolean alreadyRecorded = used.getParentNode()
                    .filter(p -> p instanceof ObjectCreationExpr
                            || p instanceof ClassOrInterfaceDeclaration)
                    .isPresent();
            if (alreadyRecorded) continue;
            references.add(new Reference(Reference.Kind.TYPE_USE, used.getNameWithScope(),
                    used.getNameAsString(), enclosingDeclaration(used, pkg), at(file, used)));
        }
        for (FieldAccessExpr access : cu.findAll(FieldAccessExpr.class)) {
            references.add(new Reference(Reference.Kind.READ, access.getNameAsString(),
                    access.getNameAsString(), enclosingDeclaration(access, pkg), at(file, access)));
        }
    }

    /** A type's own methods, constructors and fields — only the exported ones when {@code exportsOnly}. */
    private static void collectMembers(TypeDeclaration<?> type, String qualified, Source file, boolean exportsOnly,
                                       List<Symbol> symbols) {
        // An enum's constants come first, as they do in its source. Each is public wherever its enum is.
        if (type instanceof EnumDeclaration enumeration) {
            for (EnumConstantDeclaration constant : enumeration.getEntries()) {
                symbols.add(new Symbol(Symbol.Kind.ENUM_CONSTANT, constant.getNameAsString(), qualified,
                        qualified + "#" + constant.getNameAsString(), at(file, constant), List.of()));
            }
        }
        for (MethodDeclaration method : type.getMethods()) {
            if (exportsOnly && !visible(method)) continue;
            symbols.add(new Symbol(Symbol.Kind.METHOD, method.getNameAsString(), qualified,
                    qualified + "#" + method.getNameAsString() + params(method.getParameters()),
                    at(file, method), List.of()));
        }
        for (ConstructorDeclaration ctor : type.getConstructors()) {
            if (exportsOnly && (type instanceof EnumDeclaration || !visible(ctor))) continue;
            symbols.add(new Symbol(Symbol.Kind.CONSTRUCTOR, ctor.getNameAsString(), qualified,
                    qualified + "#<init>" + params(ctor.getParameters()),
                    at(file, ctor), List.of()));
        }
        for (FieldDeclaration field : type.getFields()) {
            if (exportsOnly && !visible(field)) continue;
            for (VariableDeclarator variable : field.getVariables()) {
                symbols.add(new Symbol(Symbol.Kind.FIELD, variable.getNameAsString(), qualified,
                        qualified + "#" + variable.getNameAsString(),
                        at(file, variable), List.of()));
            }
        }
        // A record's components are fields as far as an index is concerned: they are named, they are
        // declared here, and someone looking for the name expects to land on them. Each has a public accessor,
        // so an exported record exports them all.
        if (type instanceof RecordDeclaration record) {
            for (Parameter component : record.getParameters()) {
                symbols.add(new Symbol(Symbol.Kind.FIELD, component.getNameAsString(), qualified,
                        qualified + "#" + component.getNameAsString(),
                        at(file, component), List.of()));
            }
        }
    }

    // --- visibility -----------------------------------------------------------------------------

    /**
     * Whether code outside the library can name this type: a public top-level type, or a visible member type of
     * an exported one. A local or anonymous class's type is nobody else's to name.
     */
    private static boolean exported(TypeDeclaration<?> type) {
        Node parent = type.getParentNode().orElse(null);
        if (parent instanceof CompilationUnit) return type.getAccessSpecifier() == AccessSpecifier.PUBLIC;
        if (parent instanceof TypeDeclaration<?> outer) return visible(type) && exported(outer);
        return false;
    }

    /** Whether a member is public or protected, as written or as its interface or annotation makes it. */
    private static boolean visible(NodeWithModifiers<?> member) {
        return switch (member.getAccessSpecifier()) {
            case PUBLIC, PROTECTED -> true;
            case PRIVATE -> false;
            case NONE -> ((Node) member).getParentNode()
                    .map(p -> p instanceof AnnotationDeclaration
                            || p instanceof ClassOrInterfaceDeclaration decl && decl.isInterface())
                    .orElse(false);
        };
    }

    // --- naming ---------------------------------------------------------------------------------

    private static String packageOf(CompilationUnit cu) {
        return cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
    }

    private static Symbol.Kind kindOf(TypeDeclaration<?> type) {
        if (type instanceof RecordDeclaration) return Symbol.Kind.RECORD;
        if (type instanceof EnumDeclaration) return Symbol.Kind.ENUM;
        if (type instanceof AnnotationDeclaration) return Symbol.Kind.ANNOTATION;
        if (type instanceof ClassOrInterfaceDeclaration decl && decl.isInterface()) return Symbol.Kind.INTERFACE;
        return Symbol.Kind.CLASS;
    }

    private static List<String> supersOf(TypeDeclaration<?> type) {
        if (!(type instanceof ClassOrInterfaceDeclaration decl)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        decl.getExtendedTypes().forEach(t -> out.add(t.getNameAsString()));
        decl.getImplementedTypes().forEach(t -> out.add(t.getNameAsString()));
        return out;
    }

    /** {@code a.b.Outer.Inner} — package plus every enclosing type. */
    private static String qualifiedTypeName(TypeDeclaration<?> type, String pkg) {
        List<String> parts = new ArrayList<>();
        parts.add(type.getNameAsString());
        Optional<Node> parent = type.getParentNode();
        while (parent.isPresent() && parent.get() instanceof TypeDeclaration<?> outer) {
            parts.add(0, outer.getNameAsString());
            parent = outer.getParentNode();
        }
        String nested = String.join(".", parts);
        return pkg.isEmpty() ? nested : pkg + "." + nested;
    }

    private static String enclosing(TypeDeclaration<?> type, String pkg) {
        Optional<Node> parent = type.getParentNode();
        if (parent.isPresent() && parent.get() instanceof TypeDeclaration<?> outer) {
            return qualifiedTypeName(outer, pkg);
        }
        return pkg;
    }

    /** The qualified name of the declaration a use sits inside, for "who refers to this". */
    private static String enclosingDeclaration(Node node, String pkg) {
        Node current = node;
        while (current != null) {
            if (current instanceof MethodDeclaration method) {
                Optional<Node> owner = method.getParentNode();
                String type = owner.isPresent() && owner.get() instanceof TypeDeclaration<?> t
                        ? qualifiedTypeName(t, pkg) : pkg;
                return type + "#" + method.getNameAsString() + params(method.getParameters());
            }
            if (current instanceof TypeDeclaration<?> type) {
                return qualifiedTypeName(type, pkg);
            }
            current = current.getParentNode().orElse(null);
        }
        return pkg;
    }

    private static String params(List<Parameter> parameters) {
        List<String> names = parameters.stream().map(p -> p.getType().asString()).toList();
        return "(" + String.join(",", names) + ")";
    }

    private static SourceRef at(Source file, Node node) {
        return node.getBegin()
                .map(p -> new SourceRef(file, p.line, p.column))
                .orElse(new SourceRef(file, 1, 1));
    }

    /** The name a declaration node carries, when it has one. */
    static String nameOf(Node node) {
        return node instanceof NodeWithSimpleName<?> named ? named.getNameAsString() : "";
    }
}
