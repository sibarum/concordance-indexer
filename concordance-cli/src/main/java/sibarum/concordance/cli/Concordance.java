package sibarum.concordance.cli;

import sibarum.concordance.index.Hit;
import sibarum.concordance.index.Index;
import sibarum.concordance.index.JarCache;
import sibarum.concordance.index.LibraryFile;
import sibarum.concordance.index.Limits;
import sibarum.concordance.index.NameGroup;
import sibarum.concordance.index.NameGroups;
import sibarum.concordance.index.Plan;
import sibarum.concordance.index.ProjectIndex;
import sibarum.concordance.index.Query;
import sibarum.concordance.index.Reference;
import sibarum.concordance.index.Search;
import sibarum.concordance.index.Source;
import sibarum.concordance.index.SourceRef;
import sibarum.concordance.index.Symbol;
import sibarum.concordance.project.Classpath;
import sibarum.concordance.project.Dependency;
import sibarum.concordance.project.DependencyResolver;
import sibarum.concordance.project.LocalRepository;
import sibarum.concordance.project.MavenProject;
import sibarum.concordance.project.MavenProjectReader;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Concordance from a terminal.
 *
 * <pre>{@code
 * concordance <project> names <fragment>
 * concordance <project> usages <name>
 * concordance <project> impls <type> [method]
 * concordance <project> summary
 * concordance <project> deps
 * concordance <project> show <name>
 * concordance <project> search <fragment> [--recent <file> ...]
 * concordance <project> <query> [args] [--libraries] [--sources <jar> ...]
 * concordance cache [list | purge | prune <size>] [--jar-cache <dir>]
 * concordance export <jar> [<out>]
 * }</pre>
 *
 * <p>Output is one result per line, {@code path:line} first, so it pipes into anything that opens files —
 * {@code concordance . usages area | cut -d' ' -f1} and onwards. The editor's commands present the same index as
 * rows instead, which is the same information shaped for a shell that has tables.
 */
public final class Concordance {

    private Concordance() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Separated from {@link #main} so a test can drive it without exiting the JVM. */
    public static int run(String[] given, PrintStream out, PrintStream err) {
        // Flags may appear anywhere after the project; everything else is positional.
        List<String> positional = new ArrayList<>();
        List<Path> jars = new ArrayList<>();
        List<Path> classJars = new ArrayList<>();
        List<Path> recent = new ArrayList<>();
        boolean libraries = false;
        boolean jarCache = true;
        Path jarCacheDir = null;
        for (int i = 0; i < given.length; i++) {
            if (given[i].equals("--sources")) {
                if (++i == given.length) return missing(err, "--sources", "<jar>");
                jars.add(Path.of(given[i]));
            } else if (given[i].equals("--recent")) {
                if (++i == given.length) return missing(err, "--recent", "<file>");
                recent.add(Path.of(given[i]));
            } else if (given[i].equals("--jar-cache")) {
                if (++i == given.length) return missing(err, "--jar-cache", "<dir>");
                jarCacheDir = Path.of(given[i]);
            } else if (given[i].equals("--no-jar-cache")) {
                jarCache = false;
            } else if (given[i].equals("--libraries")) {
                libraries = true;
            } else {
                positional.add(given[i]);
            }
        }
        String[] args = positional.toArray(String[]::new);
        JarCache cache = jarCacheDir != null ? JarCache.at(jarCacheDir) : JarCache.defaultLocation();
        // "cache" alone, or followed by one of its own words, is the cache; otherwise it is a project directory.
        if (args.length >= 1 && args[0].equals("cache")
                && (args.length == 1 || List.of("prune", "list", "purge").contains(args[1]))) {
            return cache(cache, args, out, err);
        }
        // Likewise "export" followed by a jar that exists; a project directory called export still works.
        if (args.length >= 2 && args[0].equals("export") && Files.isRegularFile(Path.of(args[1]))) {
            return export(args, out, err);
        }
        if (args.length < 2) {
            usage(err);
            return 2;
        }
        Path root = Path.of(args[0]);
        String command = args[1];

        Index index;
        ProjectIndex built;
        ProjectIndex.Changes changes;
        Classpath classpath = null;
        MavenProject project;
        long started = System.nanoTime();
        try {
            project = MavenProjectReader.read(root);
            if (command.equals("deps")) {
                return deps(project, out);
            }
            if (libraries) {
                classpath = DependencyResolver.resolve(project, LocalRepository.locate());
                jars.addAll(classpath.sourcesJars());
                classpath.withoutSources().stream().map(Dependency::jar).filter(java.util.Objects::nonNull)
                        .forEach(classJars::add);
                classpath.problems().forEach(p -> err.println("! " + p));
            }
            built = ProjectIndex.empty(project.rootDir());
            if (jarCache) built.useJarCache(cache);
            changes = built.synchronize(project.javaFiles(), jars, classJars);
            built.problems().forEach(p -> err.println("! " + p));
            index = built.index();
        } catch (IOException e) {
            err.println("cannot index " + root + ": " + e.getMessage());
            return 1;
        }
        long millis = (System.nanoTime() - started) / 1_000_000;

        switch (command) {
            case "summary" -> {
                int files;
                try {
                    files = project.javaFiles().size();
                } catch (IOException e) {
                    files = -1;
                }
                out.println(root + ": " + index.summary());
                out.println("  " + project.modules().size() + " modules, " + files + " files, "
                        + jars.size() + " sources jars, " + classJars.size() + " class jars"
                        + (changes.fromCache() > 0 ? " (" + changes.fromCache() + " from the jar cache"
                                + (changes.unhashed() > 0 ? ", " + changes.unhashed() + " without reading them" : "") + ")" : "")
                        + (changes.prebuilt() > 0 ? " (" + changes.prebuilt() + " prebuilt)" : "")
                        + ", indexed in " + millis + " ms");
            }
            case "names" -> {
                if (args.length < 3) return missing(err, "names", "<fragment>");
                List<Symbol> hits = index.namesContaining(args[2]);
                Map<String, List<Symbol>> byName = new LinkedHashMap<>();
                hits.forEach(s -> byName.computeIfAbsent(s.name(), k -> new ArrayList<>()).add(s));
                byName.forEach((name, declared) -> printGroup(out, name, declared, declared.size()));
                note(out, byName.size(), "names (" + hits.size() + " declarations) answering " + args[2], millis);
            }
            case "usages" -> {
                if (args.length < 3) return missing(err, "usages", "<name>");
                List<Reference> uses = index.usagesOf(args[2]);
                uses.forEach(r -> out.printf("%s:%d  %-10s in %s%n",
                        r.at().source(), r.at().line(), r.kind(), r.from()));
                note(out, uses.size(), "uses of " + args[2], millis);
            }
            case "impls" -> {
                if (args.length < 3) return missing(err, "impls", "<type> [method]");
                List<Symbol> hits = args.length > 3
                        ? index.implementationsOfMethod(args[2], args[3])
                        : index.implementationsOf(args[2]);
                hits.forEach(s -> out.println(line(s)));
                note(out, hits.size(), args.length > 3
                        ? "implementations of " + args[2] + "." + args[3]
                        : "types implementing or extending " + args[2], millis);
            }
            case "show" -> {
                if (args.length < 3) return missing(err, "show", "<name>");
                return show(index, args[2], out, err);
            }
            case "search" -> {
                if (args.length < 3) return missing(err, "search", "<fragment>");
                return search(built, Plan.of(recent.stream().map(Source::of).toList(), classpath), args[2],
                        millis, out, err);
            }
            default -> {
                err.println("no such query: " + command);
                usage(err);
                return 2;
            }
        }
        return 0;
    }

    /**
     * Declarations whose name answers {@code fragment}, stage by stage as a {@link Search} finds them: what an
     * editor's "go to symbol" box shows while it fills in. Each stage prints as it completes, best fit first.
     */
    private static int search(ProjectIndex built, Plan plan, String fragment, long millis, PrintStream out,
                              PrintStream err) {
        long started = System.nanoTime();
        NameGroups groups = new NameGroups(new NameGroups.Listener() {
            final Map<String, NameGroup> changedHere = new LinkedHashMap<>();
            final Map<String, Integer> shownBefore = new HashMap<>();

            @Override
            public void stage(Plan.Stage stage) {
                printStage();
                out.println("== " + stage);
            }

            @Override
            public void changed(Plan.Stage stage, List<NameGroup> changed) {
                changed.forEach(g -> changedHere.put(g.name(), g));
            }

            @Override
            public void ended(Search.State state) {
                printStage();
            }

            /** Each group this stage made or grew, best fit first, showing only what the stage added. */
            private void printStage() {
                changedHere.values().stream()
                        .sorted(Comparator.comparingInt(NameGroup::rank).thenComparing(NameGroup::name))
                        .forEach(g -> {
                            int before = shownBefore.getOrDefault(g.name(), 0);
                            List<Symbol> added = g.symbols().subList(before, g.symbols().size());
                            printGroup(out, g.name(), added, g.declarations().size());
                            shownBefore.put(g.name(), g.declarations().size());
                        });
                changedHere.clear();
            }
        });
        Search<Symbol> search = Search.start(built.corpus(), plan, Query.names(fragment), groups);
        try {
            List<Hit<Symbol>> hits = search.done().get();
            long searched = (System.nanoTime() - started) / 1_000_000;
            out.println("-- " + groups.groups().size() + " names (" + hits.size() + " declarations) answering " + fragment + ", in "
                    + search.stages().size() + " stages (indexed in " + millis + " ms, searched in " + searched + " ms)");
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        } catch (java.util.concurrent.ExecutionException e) {
            err.println("search failed: " + e.getCause());
            return 1;
        }
    }

    /**
     * The text around one declaration — from its file, its sources jar, or decompiled from its class — with the
     * declaration's line marked. {@code name} is a qualified name ({@code a.b.C#m(int)}) or a simple one; a simple
     * name with several declarations lists them, which is the choice to make before one can be shown.
     */
    private static int show(Index index, String name, PrintStream out, PrintStream err) {
        List<Symbol> found = index.symbols().stream().filter(s -> s.qualified().equals(name)).toList();
        if (found.isEmpty()) found = index.symbols().stream().filter(s -> s.name().equals(name)).toList();
        if (found.isEmpty()) {
            err.println("nothing is declared as " + name);
            return 1;
        }
        if (found.size() > 1) {
            out.println(name + " has " + found.size() + " declarations; show one by its qualified name:");
            found.forEach(s -> out.println("    " + pad(s.kind().name(), 13) + " " + s.qualified()));
            return 0;
        }
        Symbol symbol = found.getFirst();
        try {
            SourceRef at = symbol.source().locate(symbol);
            List<String> lines = symbol.source().text().lines().toList();
            String how = symbol.source() instanceof Source.Decompiled
                    ? " (read-only, decompiled with " + Source.Decompiled.jdk() + ")"
                    : symbol.source().readOnly() ? " (read-only)" : "";
            out.println("== " + at + how);
            for (int n = Math.max(1, at.line() - 2); n <= Math.min(lines.size(), at.line() + 15); n++) {
                out.printf("%s%5d  %s%n", n == at.line() ? ">" : " ", n, lines.get(n - 1));
            }
            return 0;
        } catch (IOException e) {
            err.println("cannot show " + symbol.qualified() + ": " + e.getMessage());
            return 1;
        }
    }

    /**
     * A {@link LibraryFile} for one jar: its sources, or its classes decompiled, with their index prebuilt. Written
     * to {@code args[2]}, else beside the jar as its {@code -concordance.jar}, where dependency resolution finds it.
     */
    private static int export(String[] args, PrintStream out, PrintStream err) {
        Path jar = Path.of(args[1]);
        Path target = args.length > 2 ? Path.of(args[2]) : LibraryFile.defaultFor(jar);
        long started = System.nanoTime();
        try {
            LibraryFile.export(jar, target, Limits.DEFAULT);
        } catch (IOException e) {
            err.println("cannot export " + jar + ": " + e.getMessage());
            return 1;
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        out.println(target.toAbsolutePath().normalize() + ": " + bytes(target.toFile().length()) + ", made in "
                + millis + " ms");
        return 0;
    }

    /**
     * The jar cache: where it is and how much it holds; with {@code list}, each jar index in it; with {@code purge},
     * emptied; with {@code prune <size>}, trimmed to that size by dropping the least recently used jars first. A
     * dropped jar is simply indexed again the next time a project needs it.
     */
    private static int cache(JarCache cache, String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length == 1) {
                long overhead = cache.overheadBytes();
                long stale = cache.indexes().stream().filter(i -> i.stale(java.time.Instant.now())).count();
                out.println(cache.dir() + ": " + cache.count() + " jars, " + bytes(cache.sizeBytes())
                        + (overhead > 0 ? " (and " + bytes(overhead) + " of stamps and project records)" : "")
                        + (stale > 0 ? ", " + stale + " stale" : ""));
                return 0;
            }
            if (args[1].equals("list")) return cacheList(cache, out);
            if (args[1].equals("purge")) {
                int before = cache.count();
                long sizeBefore = cache.sizeBytes() + cache.overheadBytes();
                int purged = cache.purge();
                out.println("purged " + purged + " of " + before + " jars, " + bytes(sizeBefore) + " ("
                        + cache.dir() + ")");
                return 0;
            }
            if (args.length < 3) return missing(err, "cache prune", "<size>, such as 500M, 2G or 0");
            long limit;
            try {
                limit = parseSize(args[2]);
            } catch (IllegalArgumentException e) {
                err.println("cache prune: " + e.getMessage());
                return 2;
            }
            int before = cache.count();
            long sizeBefore = cache.sizeBytes();
            int pruned = cache.prune(limit);
            out.println("pruned " + pruned + " of " + before + " jars, " + bytes(sizeBefore) + " to "
                    + bytes(cache.sizeBytes()) + ", least recently used first (" + cache.dir() + ")");
            return 0;
        } catch (IOException e) {
            err.println("cannot read the jar cache at " + cache.dir() + ": " + e.getMessage());
            return 1;
        }
    }

    /**
     * Every jar index in the cache, largest first: its size, when it was last built or loaded, how it was read, how
     * many projects use it, whether a newer version of it is cached, and the jars known to hold its bytes.
     */
    private static int cacheList(JarCache cache, PrintStream out) throws IOException {
        java.time.Instant now = java.time.Instant.now();
        List<JarCache.Indexed> indexes = cache.indexes();
        long total = 0;
        int stale = 0;
        for (JarCache.Indexed i : indexes) {
            total += i.bytes();
            if (i.stale(now)) stale++;
            String used = java.time.LocalDate.ofInstant(i.lastUsed(), java.time.ZoneId.systemDefault()).toString();
            String state = i.stale(now) ? "stale" : i.outdated() ? "outdated" : "";
            String jars = i.jars().isEmpty() ? "(no jar known: " + i.digest().substring(0, 12) + ")"
                    : i.jars().getFirst() + (i.jars().size() > 1 ? " and " + (i.jars().size() - 1) + " more" : "");
            out.println(pad(bytes(i.bytes()), 10) + " " + used + "  " + pad(i.compiled() ? "classes" : "sources", 8)
                    + " " + pad(i.projects() == 1 ? "1 project" : i.projects() + " projects", 11) + " "
                    + pad(state, 8) + " " + jars);
        }
        out.println("-- " + indexes.size() + " jars, " + bytes(total) + " in " + cache.dir()
                + (stale > 0 ? "; " + stale + " stale (unused, a newer version cached, idle "
                        + JarCache.STALE_AFTER.toDays() + " days), swept by a synchronize" : ""));
        return 0;
    }

    /** {@code 500M}, {@code 2G}, {@code 64K}, or plain bytes; binary units, case ignored. */
    static long parseSize(String text) {
        String t = text.trim().toUpperCase(java.util.Locale.ROOT);
        if (t.endsWith("B")) t = t.substring(0, t.length() - 1);
        long unit = 1;
        if (!t.isEmpty()) {
            switch (t.charAt(t.length() - 1)) {
                case 'K' -> unit = 1L << 10;
                case 'M' -> unit = 1L << 20;
                case 'G' -> unit = 1L << 30;
                default -> {}
            }
        }
        String digits = unit == 1 ? t : t.substring(0, t.length() - 1);
        try {
            long n = Long.parseLong(digits.trim());
            if (n < 0) throw new NumberFormatException();
            return Math.multiplyExact(n, unit);
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("not a size: " + text + " (try 500M, 2G or 0)");
        }
    }

    private static String bytes(long n) {
        if (n < 1L << 10) return n + " bytes";
        if (n < 1L << 20) return String.format(java.util.Locale.ROOT, "%.1f KB", n / 1024.0);
        if (n < 1L << 30) return String.format(java.util.Locale.ROOT, "%.1f MB", n / (1024.0 * 1024));
        return String.format(java.util.Locale.ROOT, "%.1f GB", n / (1024.0 * 1024 * 1024));
    }

    /** The libraries the project's main source can name, nearest first, and what could not be resolved. */
    private static int deps(MavenProject project, PrintStream out) throws IOException {
        long started = System.nanoTime();
        LocalRepository repository = LocalRepository.locate();
        Classpath classpath = DependencyResolver.resolve(project, repository);
        long millis = (System.nanoTime() - started) / 1_000_000;
        for (Dependency d : classpath.dependencies()) {
            out.println(d.depth() + "  " + pad(d.scope(), 9) + " " + pad(d.coordinates().toString()
                    + (d.classifier().isEmpty() ? "" : ":" + d.classifier()), 60)
                    + (d.prebuilt() ? " prebuilt" : d.sourcesJar() != null ? " sources"
                            : d.jar() != null ? " jar only" : " missing"));
        }
        classpath.problems().forEach(p -> out.println("! " + p));
        out.println("-- " + classpath.dependencies().size() + " libraries, " + classpath.sourcesJars().size()
                + " with sources, " + classpath.problems().size() + " problems, from " + repository
                + " (resolved in " + millis + " ms)");
        return 0;
    }

    /**
     * One name: on one line when it has one declaration, otherwise a header and a line per declaration — the
     * choice an editor offers when an overloaded name is picked. {@code total} counts declarations already shown
     * under an earlier stage too, so a group that grew says so.
     */
    private static void printGroup(PrintStream out, String name, List<Symbol> shown, int total) {
        if (total == 1) {
            out.println(line(shown.getFirst()));
            return;
        }
        Map<Symbol.Kind, Long> kinds = new java.util.TreeMap<>();
        shown.forEach(s -> kinds.merge(s.kind(), 1L, Long::sum));
        String counted = kinds.entrySet().stream().map(e -> e.getValue() + " " + e.getKey())
                .collect(java.util.stream.Collectors.joining(", "));
        out.println(name + "  (" + (shown.size() == total ? total + ": " : "+" + shown.size() + " of " + total + ": ")
                + counted + ")");
        shown.forEach(s -> out.println("    " + s.at() + "  " + pad(s.kind().name(), 13) + " " + s.qualified()));
    }

    private static String line(Symbol symbol) {
        return symbol.at()
                + "  " + pad(symbol.kind().name(), 10)
                + " " + pad(symbol.name(), 28)
                + " " + symbol.owner();
    }

    private static void note(PrintStream out, int count, String what, long millis) {
        out.println("-- " + count + " " + what + " (indexed in " + millis + " ms)");
    }

    private static int missing(PrintStream err, String command, String expected) {
        err.println(command + " needs an argument: " + expected);
        return 2;
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }

    private static void usage(PrintStream err) {
        err.println("""
                concordance <project> <query> [args]

                  summary                     what the index holds
                  names   <fragment>          declarations whose name answers a fragment, best fit first
                  usages  <name>              where a name is used
                  impls   <type> [method]     types implementing a type, or methods implementing its method
                  search  <fragment>          like names, but staged as an editor searches: open files
                                              (--recent), the project, libraries it links to, the rest
                  show    <name>              the text around a declaration, decompiled when its library
                                              has no sources; a name with several declarations lists them
                  deps                        the libraries main source can name, nearest first, from the
                                              local repository, and what could not be resolved

                  --recent <file>             a file open in the editor, searched first; repeatable,
                                              most recent first
                  --libraries                 also index every resolved library: its sources jar, or
                                              failing that its class files, shown decompiled
                  --no-jar-cache              do not share library indexes through the jar cache
                                              ($CONCORDANCE_JAR_CACHE, else ~/.concordance/jars)
                  --sources <jar>             also index a library's sources jar; repeatable. A library
                                              contributes what it exports -- public and protected types
                                              and members -- and no references.

                  --jar-cache <dir>           use this jar cache rather than the default

                concordance cache [list | purge | prune <size>]

                  (alone)                     where the jar cache is, how many jars it holds, its size
                  list                        every jar index in it, largest first: its size, when it was
                                              last used, how many projects use it, the jar it is of
                  purge                       delete every jar index; each is indexed again when a project
                                              next needs it
                  prune   <size>              trim it to <size> (500M, 2G, 0), least recently used first;
                                              a pruned jar is indexed again when a project next needs it

                  The cache tidies itself: the index of what a jar's path held before goes as soon as
                  the path holds a new build, and an index no project uses, of a version older than one
                  cached, goes once it has been idle 3 days. The newest version is never dropped on its own.

                concordance export <jar> [<out>]

                  Makes a Concordance library file of a jar: its sources jar, or failing that its classes,
                  decompiled -- the text, with the index prebuilt. Written to <out>, else beside the jar as
                  its -concordance.jar, which --libraries then takes in place of the -sources.jar. It is a
                  sources jar itself, so a Concordance that would index it differently reindexes it from
                  its text; --sources <file> takes one from anywhere.

                <project> is a directory holding a pom.xml, or the pom.xml itself.

                Names are matched permissively -- case-insensitively, by prefix, by substring, by camel humps
                (MPR finds MavenProjectReader) and finally as a subsequence -- because whoever is asking may only
                half-remember the name. `names' shows every tier, best fit first. `usages' and `impls' take only
                the best tier there is, so an exact name still answers exactly.

                Matching is by name and without resolution, so usages over-approximate: everything real is found,
                and some extras with it.
                """);
    }
}
