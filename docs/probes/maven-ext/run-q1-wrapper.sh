#!/bin/sh
# Q1 via the Maven wrapper (only-script 3.3.4, generated into q1/ by
#   mvn -N org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper -Dmaven=3.9.16).
# MAVEN_USER_HOME keeps the downloaded distribution under target/ instead of ~/.m2/wrapper.
cd "$(dirname "$0")"
export MAVEN_USER_HOME="$(pwd)/target/mvnw-home"
cd q1
Q1="$(pwd)"

run() {  # $1 = config file name, $2 = its single line
    rm -f .mvn/jvm.config .mvn/maven.config
    printf '%s\n' "$2" > ".mvn/$1"
    for cwd in . sub; do
        w=$([ "$cwd" = . ] && echo . || echo ..)
        echo "=== .mvn/$1: $2"
        echo "--- git-bash: (cd q1/$cwd && $w/mvnw -B validate)"
        (cd "$Q1/$cwd" && "$w/mvnw" -B validate 2>&1) | grep -E '\[probe-ext\] afterSession|ERROR|WARN|BUILD' | sed 's/^/    /'
        W="$(cygpath -w "$Q1/$cwd")"
        echo "--- cmd: cd /d $W && $w\mvnw.cmd -B validate"
        cmd //c "cd /d $W && $w\mvnw.cmd -B validate" 2>&1 | grep -E '\[probe-ext\] afterSession|ERROR|WARN|BUILD' | sed 's/^/    /'
    done
}

run jvm.config   '-Dmaven.ext.class.path=.mvn/ext/probe-ext.jar'
run maven.config '-Dmaven.ext.class.path=.mvn/ext/probe-ext.jar'
run maven.config '-Dmaven.ext.class.path=${maven.multiModuleProjectDirectory}/.mvn/ext/probe-ext.jar'
rm -f .mvn/jvm.config .mvn/maven.config
