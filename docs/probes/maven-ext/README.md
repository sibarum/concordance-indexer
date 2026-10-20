# Probe: a per-project core extension that serves artifacts through a WorkspaceReader

Throwaway. Settles three questions about the mechanism proposed for pulling dependency sources from GitHub
without forking Maven: a core extension jar kept in the repository, loaded through `maven.ext.class.path`
from `.mvn/`, contributing a `WorkspaceReader` under hint `ide`.

## Answers

| # | Question | Answer |
|---|----------|--------|
| 1 | Does `-Dmaven.ext.class.path=…` in `.mvn/jvm.config` load the jar as a core extension? | **Yes** |
| 1 | …in `.mvn/maven.config`? | **Yes** |
| 1 | A relative path is resolved against… | **the current directory**, not the project root. From a submodule directory the jar is not found and Maven **silently** runs without it (no warning, BUILD SUCCESS). Same through `mvnw`/`mvnw.cmd`. |
| 1 | A path that works from any directory | `.mvn/maven.config`: `-Dmaven.ext.class.path=${maven.multiModuleProjectDirectory}/.mvn/ext/probe-ext.jar` (Maven interpolates it; works for `mvn`, `mvn.cmd`, `mvnw`, `mvnw.cmd`, from root and from `sub/`). In `jvm.config` neither `${maven.multiModuleProjectDirectory}` nor `${MAVEN_PROJECTBASEDIR}` is expanded; `!MAVEN_PROJECTBASEDIR!` happens to work under `mvn.cmd` only (delayed expansion in the batch script), not under the sh launcher. |
| 2 | Is the `@Named("ide")` `WorkspaceReader` asked before any repository? | **Yes, for `findArtifact`.** It served `probe:served:1` (exists nowhere else) and *beat ~/.m2* for `com.github.javaparser:javaparser-core:3.28.2`, which is in ~/.m2: the classpath points at the reader's files. It is also asked for every plugin pom/jar. **Not for version ranges:** with a range, Maven fetched `maven-metadata.xml` from Central *before* calling `findVersions` (the reader's versions are merged with the repositories', not consulted instead of them). |
| 3 | Is the reader asked for parent poms and imported BOMs while the model is built? | **Yes.** During "Scanning for projects", before `afterProjectsRead`, it was asked for (and served) the parent `probe:served-parent:pom:1` and the imported BOM `probe:served-bom:pom:1`, neither of which exists in ~/.m2 or any repository. It is also asked for the parents/BOMs of dependency poms (`javaparser-parent`, `junit-bom`) later, during dependency resolution. |

Environment:

```
$ mvn -v
Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5)
Maven home: C:\JDK\apache-maven-3.9.16
Java version: 25.0.3, vendor: Oracle Corporation, runtime: C:\JDK\graalvm-jdk-25.0.3+9.1
Default locale: en_US, platform encoding: UTF-8
OS name: "windows 11", version: "10.0", arch: "amd64", family: "windows"
```

## Layout

- `ext/src/` — the extension: `ProbeParticipant` (`AbstractMavenLifecycleParticipant`, `@Named("probe")`, prints
  where it was loaded from and the cwd) and `ProbeWorkspaceReader` (`@Named("ide") @Singleton`, logs every
  `findArtifact`/`findVersions`, serves files from `served/<groupId>/<artifactId>-<version>[-<classifier>].<ext>`
  beside its own jar). Sisu index written by hand: `META-INF/sisu/javax.inject.Named`.
- `served-src/` — what the reader serves: `probe:served:1` (class `probe.served.Hello`), a jar published under
  `com.github.javaparser:javaparser-core:3.28.2` that contains only `probe.shadow.NotJavaParser`, and three poms:
  `served-1`, `served-parent-1` (parent), `served-bom-1` (BOM managing `probe:served` at `[1,2)`).
- `build.sh` — `javac`/`jar` against the local Maven's own `lib/*` (no dependency downloads), then copies the jar and
  `served/` into `q1/.mvn/ext/` and `q23/.mvn/ext/`.
- `q1/` — aggregator + module `sub/`, nothing to resolve. `run-q1.sh` writes one `.mvn/jvm.config` or
  `.mvn/maven.config` per case and runs `validate` with the git-bash `mvn` and with `mvn.cmd`, from `q1/` and from
  `q1/sub/`. `run-q1-wrapper.sh` does the same through the generated `mvnw`/`mvnw.cmd`.
- `q23/` — `.mvn/jvm.config` = `-Dmaven.ext.class.path=.mvn/ext/probe-ext.jar`; parent, BOM and `probe:served`
  exist only in the reader; `App.java` references `probe.served.Hello` and `probe.shadow.NotJavaParser`, so it
  compiles only if both came from the reader.

Reproduce (git bash, from this directory): `sh build.sh && sh run-q1.sh && sh run-q1-wrapper.sh`, then
`cd q23 && mvn -B compile`.

## Q1 — loading, and how a relative path resolves

`sh run-q1.sh` (filtered to `[probe-` / WARN / ERROR / BUILD lines; `<probe>` = this directory). The baseline with no
config prints no `[probe-` line, so every `[probe-ext]` line below is the extension loading.

```
=== .mvn/<none>:
--- git-bash: (cd q1/. && mvn -B validate)
    [INFO] BUILD SUCCESS
--- cmd: cd /d …\q1\ && mvn.cmd -B validate
    [INFO] BUILD SUCCESS
(same from q1/sub)

=== .mvn/jvm.config: -Dmaven.ext.class.path=.mvn/ext/probe-ext.jar
--- git-bash: (cd q1/. && mvn -B validate)
    [probe-reader] created, serving …\q1\.mvn\ext\served
    [probe-ext] afterSessionStart, loaded from file:/<probe>/q1/.mvn/ext/probe-ext.jar, cwd=…\q1, maven.ext.class.path=.mvn/ext/probe-ext.jar
    [probe-ext] afterProjectsRead, workspaceReader=org.apache.maven.internal.aether.MavenChainedWorkspaceReader@3910fe11
    [INFO] BUILD SUCCESS
--- cmd: cd /d …\q1\ && mvn.cmd -B validate
    [probe-reader] created, serving …\q1\.mvn\ext\served
    [probe-ext] afterSessionStart, loaded from file:/<probe>/q1/.mvn/ext/probe-ext.jar, cwd=…\q1, maven.ext.class.path=.mvn/ext/probe-ext.jar
    [probe-ext] afterProjectsRead, workspaceReader=org.apache.maven.internal.aether.MavenChainedWorkspaceReader@3910fe11
    [INFO] BUILD SUCCESS
=== .mvn/jvm.config: -Dmaven.ext.class.path=.mvn/ext/probe-ext.jar
--- git-bash: (cd q1/sub && mvn -B validate)
    [INFO] BUILD SUCCESS
--- cmd: cd /d …\q1\sub && mvn.cmd -B validate
    [INFO] BUILD SUCCESS

=== .mvn/maven.config: -Dmaven.ext.class.path=.mvn/ext/probe-ext.jar
--- git-bash: (cd q1/. && mvn -B validate)
    [probe-reader] created, serving …\q1\.mvn\ext\served
    [probe-ext] afterSessionStart, loaded from file:/<probe>/q1/.mvn/ext/probe-ext.jar, cwd=…\q1, maven.ext.class.path=.mvn/ext/probe-ext.jar
    [probe-ext] afterProjectsRead, workspaceReader=org.apache.maven.internal.aether.MavenChainedWorkspaceReader@2d000e80
    [INFO] BUILD SUCCESS
--- cmd: cd /d …\q1\ && mvn.cmd -B validate
    [probe-reader] created, serving …\q1\.mvn\ext\served
    [probe-ext] afterSessionStart, loaded from file:/<probe>/q1/.mvn/ext/probe-ext.jar, cwd=…\q1, maven.ext.class.path=.mvn/ext/probe-ext.jar
    [probe-ext] afterProjectsRead, workspaceReader=org.apache.maven.internal.aether.MavenChainedWorkspaceReader@252f626c
    [INFO] BUILD SUCCESS
=== .mvn/maven.config: -Dmaven.ext.class.path=.mvn/ext/probe-ext.jar
--- git-bash: (cd q1/sub && mvn -B validate)
    [INFO] BUILD SUCCESS
--- cmd: cd /d …\q1\sub && mvn.cmd -B validate
    [INFO] BUILD SUCCESS

=== .mvn/jvm.config: -Dmaven.ext.class.path=${maven.multiModuleProjectDirectory}/.mvn/ext/probe-ext.jar
    (no [probe- line, BUILD SUCCESS: git-bash and cmd, from q1 and from q1/sub)

=== .mvn/maven.config: -Dmaven.ext.class.path=${maven.multiModuleProjectDirectory}/.mvn/ext/probe-ext.jar
--- git-bash: (cd q1/. && mvn -B validate)
    [probe-ext] afterSessionStart, loaded from file:/<probe>/q1/.mvn/ext/probe-ext.jar, cwd=…\q1, maven.ext.class.path=<probe>/q1/.mvn/ext/probe-ext.jar
    [INFO] BUILD SUCCESS
--- cmd: cd /d …\q1\ && mvn.cmd -B validate
    [probe-ext] afterSessionStart, loaded from file:/<probe>/q1/.mvn/ext/probe-ext.jar, cwd=…\q1, maven.ext.class.path=C:\…\maven-ext\q1/.mvn/ext/probe-ext.jar
    [INFO] BUILD SUCCESS
=== .mvn/maven.config: -Dmaven.ext.class.path=${maven.multiModuleProjectDirectory}/.mvn/ext/probe-ext.jar
--- git-bash: (cd q1/sub && mvn -B validate)
    [probe-ext] afterSessionStart, loaded from file:/<probe>/q1/.mvn/ext/probe-ext.jar, cwd=…\q1\sub, maven.ext.class.path=<probe>/q1/.mvn/ext/probe-ext.jar
    [INFO] BUILD SUCCESS
--- cmd: cd /d …\q1\sub && mvn.cmd -B validate
    [probe-ext] afterSessionStart, loaded from file:/<probe>/q1/.mvn/ext/probe-ext.jar, cwd=…\q1\sub, maven.ext.class.path=C:\…\maven-ext\q1/.mvn/ext/probe-ext.jar
    [INFO] BUILD SUCCESS

=== .mvn/jvm.config: -Dmaven.ext.class.path=${MAVEN_PROJECTBASEDIR}/.mvn/ext/probe-ext.jar
    (no [probe- line, BUILD SUCCESS: git-bash and cmd, from q1 and from q1/sub)

=== .mvn/jvm.config: -Dmaven.ext.class.path=!MAVEN_PROJECTBASEDIR!/.mvn/ext/probe-ext.jar
--- git-bash: (cd q1/. && mvn -B validate)
    [INFO] BUILD SUCCESS
--- cmd: cd /d …\q1\ && mvn.cmd -B validate
    [probe-ext] afterSessionStart, loaded from file:/<probe>/q1/.mvn/ext/probe-ext.jar, cwd=…\q1, maven.ext.class.path=C:\…\maven-ext\q1/.mvn/ext/probe-ext.jar
    [INFO] BUILD SUCCESS
(from q1/sub: git-bash no line; cmd loads it the same way)
```

Readings:

- Both files work as carriers. `maven.config` is the better one: Maven itself interpolates
  `${maven.multiModuleProjectDirectory}` in it (the `maven.ext.class.path` the extension saw is the absolute path).
  `jvm.config` is pasted onto the `java` command line by the launcher scripts and nothing interpolates it, except the
  accidental `!VAR!` delayed expansion in `mvn.cmd`.
- A relative path is resolved against the JVM's working directory (`cwd=` in the log). The `.mvn/` directory is
  still found from `sub/` (the launcher walks up to it), so the config is read, but `.mvn/ext/probe-ext.jar` then
  means `sub/.mvn/ext/probe-ext.jar`, which does not exist — and Maven says nothing about a missing
  `maven.ext.class.path` entry. A design relying on this must check that the extension actually loaded.
- The extension's `WorkspaceReader` ends up inside `MavenChainedWorkspaceReader` (Maven chains it with the reactor's
  reader).

### Through the wrapper

Generated with `mvn -B -N org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper -Dmaven=3.9.16`
(`distributionType=only-script`). `run-q1-wrapper.sh` sets `MAVEN_USER_HOME=<probe>/target/mvnw-home` so the
downloaded Maven 3.9.16 lands under `target/`, not `~/.m2/wrapper`. Same outcome as plain `mvn`:

```
=== .mvn/jvm.config: -Dmaven.ext.class.path=.mvn/ext/probe-ext.jar
--- git-bash: (cd q1/. && ./mvnw -B validate)
    [probe-ext] afterSessionStart, loaded from file:/<probe>/q1/.mvn/ext/probe-ext.jar, cwd=…\q1, maven.ext.class.path=.mvn/ext/probe-ext.jar
    [INFO] BUILD SUCCESS
--- cmd: cd /d …\q1\ && .\mvnw.cmd -B validate
    [probe-ext] afterSessionStart, loaded from file:/<probe>/q1/.mvn/ext/probe-ext.jar, cwd=…\q1, maven.ext.class.path=.mvn/ext/probe-ext.jar
    [INFO] BUILD SUCCESS
--- git-bash: (cd q1/sub && ../mvnw -B validate)
    [INFO] BUILD SUCCESS
--- cmd: cd /d …\q1\sub && ..\mvnw.cmd -B validate
    [INFO] BUILD SUCCESS
=== .mvn/maven.config: -Dmaven.ext.class.path=.mvn/ext/probe-ext.jar
    (loads from q1 with ./mvnw and .\mvnw.cmd; nothing from q1/sub)
=== .mvn/maven.config: -Dmaven.ext.class.path=${maven.multiModuleProjectDirectory}/.mvn/ext/probe-ext.jar
--- git-bash: (cd q1/. && ./mvnw -B validate)
    [probe-ext] afterSessionStart, loaded from file:/<probe>/q1/.mvn/ext/probe-ext.jar, cwd=…\q1, maven.ext.class.path=<probe>/q1/.mvn/ext/probe-ext.jar
--- cmd: cd /d …\q1\ && .\mvnw.cmd -B validate
    [probe-ext] afterSessionStart, loaded from file:/<probe>/q1/.mvn/ext/probe-ext.jar, cwd=…\q1, maven.ext.class.path=C:\…\maven-ext\q1/.mvn/ext/probe-ext.jar
--- git-bash: (cd q1/sub && ../mvnw -B validate)
    [probe-ext] afterSessionStart, loaded from file:/<probe>/q1/.mvn/ext/probe-ext.jar, cwd=…\q1\sub, maven.ext.class.path=<probe>/q1/.mvn/ext/probe-ext.jar
--- cmd: cd /d …\q1\sub && ..\mvnw.cmd -B validate
    [probe-ext] afterSessionStart, loaded from file:/<probe>/q1/.mvn/ext/probe-ext.jar, cwd=…\q1\sub, maven.ext.class.path=C:\…\maven-ext\q1/.mvn/ext/probe-ext.jar
(all BUILD SUCCESS)
```

## Q2 and Q3 — the `ide` WorkspaceReader

`cd q23 && mvn -B compile` (exit 0). Every `[probe-` line in order, except the later `-> null` lookups (81 in all) of the
resources/compiler plugins and their own dependencies (all asked of the reader, all served by ~/.m2 afterwards):

```
[probe-reader] created, serving …\q23\.mvn\ext\served
[probe-ext] afterSessionStart, loaded from file:/<probe>/q23/.mvn/ext/probe-ext.jar, cwd=…\q23, maven.ext.class.path=.mvn/ext/probe-ext.jar
[INFO] Scanning for projects...
[probe-reader] findArtifact probe:served-parent:pom:1 -> SERVED …\q23\.mvn\ext\served\probe\served-parent-1.pom
[probe-reader] findArtifact probe:served-bom:pom:1 -> SERVED …\q23\.mvn\ext\served\probe\served-bom-1.pom
[probe-reader] findArtifact probe:served-parent:pom:1 -> SERVED …\q23\.mvn\ext\served\probe\served-parent-1.pom
[probe-ext] afterProjectsRead, workspaceReader=org.apache.maven.internal.aether.MavenChainedWorkspaceReader@5b498842
[INFO] --------------------------------[ jar ]---------------------------------
[probe-reader] findArtifact org.apache.maven.plugins:maven-resources-plugin:pom:3.3.1 -> null
[probe-reader] findArtifact org.apache.maven.plugins:maven-plugins:pom:39 -> null
[probe-reader] findArtifact org.apache.maven:maven-parent:pom:39 -> null
[probe-reader] findArtifact org.apache:apache:pom:29 -> null
[probe-reader] findArtifact org.apache.maven.plugins:maven-resources-plugin:jar:3.3.1 -> null
[probe-reader] findArtifact org.apache.maven.plugins:maven-compiler-plugin:pom:3.13.0 -> null
[probe-reader] findArtifact org.apache.maven.plugins:maven-plugins:pom:41 -> null
[probe-reader] findArtifact org.apache.maven:maven-parent:pom:41 -> null
[probe-reader] findArtifact org.apache:apache:pom:31 -> null
[probe-reader] findArtifact org.apache.maven.plugins:maven-compiler-plugin:jar:3.13.0 -> null
[INFO] Downloading from central: https://repo.maven.apache.org/maven2/probe/served/maven-metadata.xml
[probe-reader] findVersions probe:served:jar:[1,2) -> [1]
[probe-reader] findArtifact probe:served:pom:1 -> SERVED …\q23\.mvn\ext\served\probe\served-1.pom
[probe-reader] findArtifact com.github.javaparser:javaparser-core:pom:3.28.2 -> null
[probe-reader] findArtifact com.github.javaparser:javaparser-parent:pom:3.28.2 -> null
[probe-reader] findArtifact org.junit:junit-bom:pom:5.14.4 -> null
[probe-reader] findArtifact probe:served:jar:1 -> SERVED …\q23\.mvn\ext\served\probe\served-1.jar
[probe-reader] findArtifact com.github.javaparser:javaparser-core:jar:3.28.2 -> SERVED …\q23\.mvn\ext\served\com.github.javaparser\javaparser-core-3.28.2.jar
… (compiler plugin dependencies, all -> null)
[INFO] Compiling 1 source file with javac [debug release 25] to target\classes
[INFO] BUILD SUCCESS
```

The full log has no other `Downloading` line. Offline, `mvn -B -o compile` gives the same `[probe-` sequence
without the metadata download, and also succeeds (so `findVersions` alone is enough to resolve the range).
The resolved classpath, `mvn -B -o dependency:3.7.0:build-classpath -Dmdep.outputFile=../target/cp.txt`:

```
…\q23\.mvn\ext\served\probe\served-1.jar
…\q23\.mvn\ext\served\com.github.javaparser\javaparser-core-3.28.2.jar
```

Readings:

- **Q2:** for a fixed version the reader is asked first and, when it answers, neither ~/.m2 nor a remote is
  touched: `javaparser-core-3.28.2.jar` sits in `~/.m2/repository/com/github/javaparser/javaparser-core/3.28.2/`,
  yet the build used the reader's file, and `App.java` compiled against `probe.shadow.NotJavaParser`, which exists
  only there. A reader that answers for a coordinate replaces it, so it must answer only for what it means to own.
  For the *pom* of `javaparser-core` the reader said null and Maven fell back to ~/.m2 — pom and jar can come from
  different places.
- **Q2, ranges:** `findVersions` is called, but only after the remote metadata was requested, and its answer is
  merged with the repositories' versions. A miss is also cached in the local repository: the online run left
  `~/.m2/repository/probe/served/resolver-status.properties` (the probe deleted it afterwards).
- **Q3:** parent and imported BOM go through the reader while projects are scanned (both before `afterProjectsRead`),
  so a lifecycle participant is too late to fetch them but the reader is not: if the reader is the thing that fetches
  and installs sources, it can do so for parents and BOMs too.

## Not covered

- Whether the reader is picked up *because of* hint `ide` (no control run with another hint).
- Build extensions declared in the pom (`<extensions>true</extensions>`) or `.mvn/extensions.xml`; only
  `maven.ext.class.path` was tried.
- Maven 4, and Maven versions other than 3.9.16.

## Side effects outside this directory

- `maven-wrapper-plugin` 3.3.4 and its dependencies were downloaded into `~/.m2/repository` (an ordinary plugin
  resolution).
- `~/.m2/repository/probe/` (the range miss above) was created by the online q23 run and then removed.
- No change to `~/.m2/settings.xml` or to `C:\JDK\apache-maven-3.9.16`.
