package sibarum.concordance.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DependencyResolverTest {

    @TempDir Path repoDir;
    @TempDir Path projectDir;

    private LocalRepository repo() {
        return LocalRepository.at(repoDir);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** {@code <dependency>} for {@code g:a:v}, plus any extra elements (scope, optional, exclusions). */
    private static String dep(String gav, String... extra) {
        String[] p = gav.split(":");
        StringBuilder sb = new StringBuilder("<dependency><groupId>").append(p[0]).append("</groupId><artifactId>")
                .append(p[1]).append("</artifactId>");
        if (p.length > 2) sb.append("<version>").append(p[2]).append("</version>");
        for (String e : extra) sb.append(e);
        return sb.append("</dependency>").toString();
    }

    private static String scope(String s) {
        return "<scope>" + s + "</scope>";
    }

    private static String excluding(String g, String a) {
        return "<exclusions><exclusion><groupId>" + g + "</groupId><artifactId>" + a
                + "</artifactId></exclusion></exclusions>";
    }

    /** Puts {@code g:a:v} in the repository: a pom with these dependencies, a jar, and a sources jar if asked. */
    private void artifact(String gav, boolean sources, String... dependencies) throws IOException {
        artifactWith(gav, sources, "<dependencies>" + String.join("", dependencies) + "</dependencies>");
    }

    private void artifactWith(String gav, boolean sources, String body) throws IOException {
        String[] p = gav.split(":");
        Coordinates c = new Coordinates(p[0], p[1], p[2]);
        write(repo().pom(c), """
                <project><groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version>%s</project>
                """.formatted(p[0], p[1], p[2], body));
        write(repo().file(c, null, "jar"), "jar");
        if (sources) write(repo().file(c, "sources", "jar"), "sources");
    }

    /** A single-module project whose pom has this body after its coordinates. */
    private MavenProject project(String body) throws IOException {
        write(projectDir.resolve("pom.xml"), """
                <project><groupId>app</groupId><artifactId>app</artifactId><version>1</version>%s</project>
                """.formatted(body));
        Files.createDirectories(projectDir.resolve("src/main/java"));
        return MavenProjectReader.read(projectDir);
    }

    private MavenProject depending(String... dependencies) throws IOException {
        return project("<dependencies>" + String.join("", dependencies) + "</dependencies>");
    }

    private Classpath resolve(MavenProject project) {
        return DependencyResolver.resolve(project, repo());
    }

    private static List<String> names(Classpath classpath) {
        return classpath.dependencies().stream()
                .map(d -> d.coordinates() + "@" + d.depth() + (d.scope().equals("compile") ? "" : "/" + d.scope()))
                .toList();
    }

    // --- the walk -------------------------------------------------------------------------------

    @Test
    void directThenTransitiveNearestFirstWithTheirSources() throws IOException {
        artifact("lib:a:1", true, dep("lib:b:1"));
        artifact("lib:b:1", false, dep("lib:c:1"));
        artifact("lib:c:1", true);
        Classpath cp = resolve(depending(dep("lib:a:1")));

        assertEquals(List.of("lib:a:1@1", "lib:b:1@2", "lib:c:1@3"), names(cp));
        assertEquals(List.of(repo().file(new Coordinates("lib", "a", "1"), "sources", "jar"),
                repo().file(new Coordinates("lib", "c", "1"), "sources", "jar")), cp.sourcesJars());
        assertEquals(List.of("lib:b:1"), cp.withoutSources().stream().map(d -> d.coordinates().toString()).toList());
        assertTrue(cp.problems().isEmpty(), cp.problems().toString());
    }

    @Test
    void eachDependencySaysWhatItWasReachedThrough() throws IOException {
        artifact("lib:a:1", true, dep("lib:b:1"));
        artifact("lib:b:1", false, dep("lib:c:1"));
        artifact("lib:c:1", true);
        Classpath cp = resolve(depending(dep("lib:a:1")));

        assertEquals(List.of("", "lib:a:1", "lib:a:1 lib:b:1"), cp.dependencies().stream()
                .map(d -> String.join(" ", d.via().stream().map(Coordinates::toString).toList())).toList());
    }

    @Test
    void onlyWhatMainSourceCanSeeIsResolved() throws IOException {
        artifact("lib:a:1", true, dep("lib:rt:1", scope("runtime")), dep("lib:prov:1", scope("provided")),
                dep("lib:t:1", scope("test")));
        artifact("lib:p:1", true, dep("lib:under-p:1"), dep("lib:rt-under-p:1", scope("runtime")));
        for (String gav : List.of("lib:rt:1", "lib:prov:1", "lib:t:1", "lib:under-p:1", "lib:rt-under-p:1",
                "lib:junit:1", "lib:direct-rt:1")) {
            artifact(gav, true);
        }
        Classpath cp = resolve(depending(dep("lib:a:1"), dep("lib:p:1", scope("provided")),
                dep("lib:junit:1", scope("test")), dep("lib:direct-rt:1", scope("runtime"))));

        assertEquals(List.of("lib:a:1@1", "lib:p:1@1/provided", "lib:under-p:1@2/provided",
                        "lib:rt-under-p:1@2/provided"), names(cp),
                "test and runtime are not visible; transitive provided and test are not inherited; "
                        + "under provided everything is provided");
    }

    @Test
    void theNearestDeclarationWinsAndTheFirstAtATie() throws IOException {
        artifact("lib:a:1", true, dep("lib:x:1"), dep("lib:y:1"));
        artifact("lib:b:1", true, dep("lib:c:1"), dep("lib:y:2"));
        artifact("lib:c:1", true, dep("lib:x:2"));
        for (String gav : List.of("lib:x:1", "lib:x:2", "lib:y:1", "lib:y:2")) artifact(gav, true);
        Classpath cp = resolve(depending(dep("lib:a:1"), dep("lib:b:1")));

        assertEquals(List.of("lib:a:1@1", "lib:b:1@1", "lib:x:1@2", "lib:y:1@2", "lib:c:1@2"), names(cp));
    }

    @Test
    void exclusionsReachEverythingBeneathAndOptionalStopsAtOneHop() throws IOException {
        artifact("lib:a:1", true, dep("lib:b:1"), dep("lib:opt:1", "<optional>true</optional>"));
        artifact("lib:b:1", true, dep("lib:gone:1"), dep("lib:kept:1"));
        for (String gav : List.of("lib:gone:1", "lib:kept:1", "lib:opt:1", "lib:mine:1")) artifact(gav, true);
        Classpath cp = resolve(depending(dep("lib:a:1", excluding("lib", "gone")),
                dep("lib:mine:1", "<optional>true</optional>")));

        assertEquals(List.of("lib:a:1@1", "lib:mine:1@1", "lib:b:1@2", "lib:kept:1@3"), names(cp));
    }

    @Test
    void aWildcardExclusionCutsOffEverything() throws IOException {
        artifact("lib:a:1", true, dep("lib:b:1"));
        artifact("lib:b:1", true);
        assertEquals(List.of("lib:a:1@1"), names(resolve(depending(dep("lib:a:1", excluding("*", "*"))))));
    }

    // --- versions -------------------------------------------------------------------------------

    @Test
    void theProjectsManagementDecidesTransitiveVersions() throws IOException {
        artifact("lib:a:1", true, dep("lib:x:1"));
        artifact("lib:x:1", true);
        artifact("lib:x:3", true);
        Classpath cp = resolve(project("""
                <dependencyManagement><dependencies>%s</dependencies></dependencyManagement>
                <dependencies>%s</dependencies>
                """.formatted(dep("lib:x:3"), dep("lib:a:1"))));
        assertEquals(List.of("lib:a:1@1", "lib:x:3@2"), names(cp));
    }

    @Test
    void anImportedBomManagesVersionsAndTheProjectsOwnEntriesWin() throws IOException {
        artifactWith("lib:bom:1", false, """
                <packaging>pom</packaging>
                <dependencyManagement><dependencies>%s%s</dependencies></dependencyManagement>
                """.formatted(dep("lib:a:2"), dep("lib:b:2")));
        artifact("lib:a:2", true);
        artifact("lib:b:1", true);
        Classpath cp = resolve(project("""
                <dependencyManagement><dependencies>%s%s</dependencies></dependencyManagement>
                <dependencies>%s%s</dependencies>
                """.formatted(dep("lib:bom:1", scope("import"), "<type>pom</type>"), dep("lib:b:1"),
                dep("lib:a"), dep("lib:b"))));
        assertEquals(List.of("lib:a:2@1", "lib:b:1@1"), names(cp));
    }

    @Test
    void aParentInTheRepositoryLendsItsPropertiesManagementAndDependencies() throws IOException {
        artifactWith("corp:parent:7", false, """
                <packaging>pom</packaging>
                <properties><lib.version>4</lib.version></properties>
                <dependencyManagement><dependencies>%s</dependencies></dependencyManagement>
                <dependencies>%s</dependencies>
                """.formatted(dep("lib:a:${lib.version}"), dep("lib:everywhere:1")));
        artifact("lib:a:4", true);
        artifact("lib:everywhere:1", true);
        write(projectDir.resolve("pom.xml"), """
                <project>
                  <parent><groupId>corp</groupId><artifactId>parent</artifactId><version>7</version></parent>
                  <artifactId>app</artifactId>
                  <dependencies>%s</dependencies>
                </project>
                """.formatted(dep("lib:a")));
        Files.createDirectories(projectDir.resolve("src/main/java"));

        Classpath cp = resolve(MavenProjectReader.read(projectDir));
        assertEquals(List.of("lib:a:4@1", "lib:everywhere:1@1"), names(cp));
        assertTrue(cp.problems().isEmpty(), cp.problems().toString());
    }

    @Test
    void aRangeTakesTheHighestLocalVersionInside() throws IOException {
        for (String v : List.of("1.0", "1.5", "2.0", "2.1-SNAPSHOT")) artifact("lib:r:" + v, true);
        assertEquals(List.of("lib:r:1.5@1"), names(resolve(depending(dep("lib:r:[1.0,2.0)")))));
    }

    // --- the reactor ----------------------------------------------------------------------------

    @Test
    void aSiblingModuleIsSourceNotALibraryButWhatItNeedsIsFollowed() throws IOException {
        artifact("lib:core-dep:1", true);
        artifact("lib:app-dep:1", true);
        write(projectDir.resolve("pom.xml"), """
                <project><groupId>demo</groupId><artifactId>demo</artifactId><version>1</version>
                  <packaging>pom</packaging><modules><module>core</module><module>app</module></modules>
                </project>
                """);
        write(projectDir.resolve("core/pom.xml"), """
                <project><parent><groupId>demo</groupId><artifactId>demo</artifactId><version>1</version></parent>
                  <artifactId>core</artifactId><dependencies>%s</dependencies></project>
                """.formatted(dep("lib:core-dep:1")));
        write(projectDir.resolve("app/pom.xml"), """
                <project><parent><groupId>demo</groupId><artifactId>demo</artifactId><version>1</version></parent>
                  <artifactId>app</artifactId><dependencies>%s%s</dependencies></project>
                """.formatted(dep("demo:core:${project.version}"), dep("lib:app-dep:1")));
        Files.createDirectories(projectDir.resolve("core/src/main/java"));
        Files.createDirectories(projectDir.resolve("app/src/main/java"));
        MavenProject project = MavenProjectReader.read(projectDir);

        MavenModule app = project.moduleNamed("app").orElseThrow();
        assertEquals(List.of("lib:app-dep:1@1", "lib:core-dep:1@2"),
                names(DependencyResolver.resolve(project, app, repo())),
                "core is in the reactor, so it is not listed, but what it depends on is");
        assertEquals(List.of("lib:core-dep:1@1", "lib:app-dep:1@1"), names(resolve(project)),
                "across the project, each library at the nearest depth any module reaches it");
    }

    // --- what cannot be done is said ----------------------------------------------------------

    @Test
    void whatCannotBeResolvedIsReportedAndTheRestStillResolves() throws IOException {
        artifact("lib:fine:1", true);
        Path orphanJar = repo().file(new Coordinates("lib", "nopom", "1"), null, "jar");
        write(orphanJar, "jar");
        Classpath cp = resolve(depending(dep("lib:nopom:1"), dep("lib:unset:${nope}"), dep("lib:r:[9,10)"),
                dep("lib:fine:1")));

        assertEquals(List.of("lib:nopom:1@1", "lib:fine:1@1"), names(cp));
        assertEquals(orphanJar, cp.dependencies().getFirst().jar());
        assertEquals(3, cp.problems().size(), cp.problems().toString());
        assertTrue(cp.problems().stream().anyMatch(p -> p.contains("lib:nopom:1") && p.contains("unknown")));
        assertTrue(cp.problems().stream().anyMatch(p -> p.contains("${nope}")));
        assertTrue(cp.problems().stream().anyMatch(p -> p.contains("[9,10)")));
    }

    @Test
    void aMissingParentIsReportedNotFatal() throws IOException {
        artifact("lib:a:1", true);
        write(projectDir.resolve("pom.xml"), """
                <project><parent><groupId>gone</groupId><artifactId>gone</artifactId><version>1</version></parent>
                  <groupId>app</groupId><artifactId>app</artifactId><version>1</version>
                  <dependencies>%s</dependencies></project>
                """.formatted(dep("lib:a:1")));
        Files.createDirectories(projectDir.resolve("src/main/java"));
        Classpath cp = resolve(MavenProjectReader.read(projectDir));
        assertEquals(List.of("lib:a:1@1"), names(cp));
        assertTrue(cp.problems().stream().anyMatch(p -> p.contains("gone:gone:1")), cp.problems().toString());
    }

    @Test
    void propertiesThatMultiplyEachOtherAreCappedAndReported() throws IOException {
        // Each level refers to the next eight times: fully expanded, p0 would be 8^12 characters.
        StringBuilder props = new StringBuilder("<properties>");
        for (int i = 0; i < 12; i++) {
            props.append("<p").append(i).append(">").append(("${p" + (i + 1) + "}").repeat(8))
                    .append("</p").append(i).append(">");
        }
        props.append("<p12>x</p12></properties>");
        artifact("lib:a:1", true);
        long started = System.nanoTime();
        Classpath cp = resolve(project(props + "<dependencies>" + dep("lib:a:1") + "</dependencies>"));

        assertTrue(System.nanoTime() - started < 10_000_000_000L, "bounded work, not an exponential one");
        assertEquals(List.of("lib:a:1@1"), names(cp), "the rest of the pom still resolves");
        assertTrue(cp.problems().stream().anyMatch(p -> p.contains("property p0") && p.contains("expands past")),
                cp.problems().toString());
    }

    @Test
    void anOversizedPomInTheRepositoryIsAProblemNotARead() throws IOException {
        artifact("lib:a:1", true, dep("lib:huge:1"));
        Coordinates huge = new Coordinates("lib", "huge", "1");
        Files.createDirectories(repo().pom(huge).getParent());
        try (var out = Files.newOutputStream(repo().pom(huge))) {
            out.write("<project><!--".getBytes());
            out.write(new byte[(int) Xml.MAX_BYTES]);
        }
        write(repo().file(huge, null, "jar"), "jar");
        Classpath cp = resolve(depending(dep("lib:a:1")));

        assertEquals(List.of("lib:a:1@1", "lib:huge:1@2"), names(cp));
        assertTrue(cp.problems().stream().anyMatch(p -> p.contains("huge") && p.contains("larger than")),
                cp.problems().toString());
    }

    // --- the repository and versions ----------------------------------------------------------

    @Test
    void settingsCanMoveTheLocalRepository(@TempDir Path home) throws IOException {
        Path settings = home.resolve(".m2/settings.xml");
        write(settings, "<settings><localRepository>${user.home}/elsewhere</localRepository></settings>");
        assertEquals(Optional.of(Path.of(home + "/elsewhere")), LocalRepository.fromSettings(settings, home));
        assertEquals(Optional.empty(), LocalRepository.fromSettings(home.resolve("absent.xml"), home));
    }

    @Test
    void versionsOrderAsMavenOrdersThem() {
        List<String> ascending = List.of("1.0-alpha-1", "1.0-beta", "1.0-m1", "1.0-rc1", "1.0-SNAPSHOT", "1.0",
                "1.0-sp1", "1.0.1", "1.1", "1.10", "2");
        for (int i = 0; i + 1 < ascending.size(); i++) {
            assertTrue(Versions.compare(ascending.get(i), ascending.get(i + 1)) < 0,
                    ascending.get(i) + " < " + ascending.get(i + 1));
        }
        assertEquals(0, Versions.compare("1", "1.0.0"));
        assertEquals(0, Versions.compare("1.0-ga", "1.0"));
        assertEquals(0, Versions.compare("1.0a1", "1.0-alpha-1"));
    }

    @Test
    void rangesAdmitWhatTheirBracketsSay() {
        List<String> local = List.of("0.9", "1.0", "1.2", "1.5", "2.0");
        assertEquals(Optional.of("1.5"), Versions.select("[1.0,2.0)", local));
        assertEquals(Optional.of("2.0"), Versions.select("[1.0,)", local));
        assertEquals(Optional.of("1.0"), Versions.select("(,1.0]", local));
        assertEquals(Optional.of("1.2"), Versions.select("[1.2]", local));
        assertEquals(Optional.of("2.0"), Versions.select("(,1.0],[1.2,)", local));
        assertEquals(Optional.empty(), Versions.select("[3,4)", local));
        assertEquals(Optional.of("1.7"), Versions.select("1.7", local), "a plain version is itself");
    }
}
