#!/bin/sh
# Builds the extension and the artifacts it serves with javac/jar against the local Maven's own lib/,
# then drops them into q1/.mvn/ext and q23/.mvn/ext (the "jar kept in the repo" layout).
set -e
cd "$(dirname "$0")"
MVN_LIB="$(dirname "$(dirname "$(command -v mvn)")")/lib"
command -v cygpath >/dev/null && MVN_LIB="$(cygpath -m "$MVN_LIB")"   # javac on Windows needs C:/... for the lib/* wildcard
rm -rf target && mkdir -p target/ext-classes target/served-classes target/shadow-classes \
    target/ext/served/probe target/ext/served/com.github.javaparser

javac --release 25 -d target/ext-classes -cp "$MVN_LIB/*" $(find ext/src -name '*.java')
cp -r ext/src/META-INF target/ext-classes/
jar cf target/ext/probe-ext.jar -C target/ext-classes .

javac --release 25 -d target/served-classes served-src/served/probe/served/Hello.java
jar cf target/ext/served/probe/served-1.jar -C target/served-classes .
javac --release 25 -d target/shadow-classes served-src/shadow/probe/shadow/NotJavaParser.java
jar cf target/ext/served/com.github.javaparser/javaparser-core-3.28.2.jar -C target/shadow-classes .
cp served-src/poms/*.pom target/ext/served/probe/

for p in q1 q23; do rm -rf $p/.mvn/ext; mkdir -p $p/.mvn; cp -r target/ext $p/.mvn/ext; done
find target/ext -type f
