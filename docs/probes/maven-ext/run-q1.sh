#!/bin/sh
# Q1: one case per (config file, value, launcher, cwd). Prints the config, the command and the lines that matter.
# Usage: sh run-q1.sh   (after build.sh)
cd "$(dirname "$0")/q1"
Q1="$(pwd)"

run() {  # $1 = config file name, $2 = its single line
    rm -f .mvn/jvm.config .mvn/maven.config
    [ -n "$1" ] && printf '%s\n' "$2" > ".mvn/$1"
    for cwd in . sub; do
        echo "=== .mvn/${1:-<none>}: ${2:-}"
        echo "--- git-bash: (cd q1/$cwd && mvn -B validate)"
        (cd "$Q1/$cwd" && mvn -B validate 2>&1) | grep -E '\[probe-|ERROR|WARN|BUILD' | sed 's/^/    /'
        W="$(cygpath -w "$Q1/$cwd")"
        echo "--- cmd: cd /d $W && mvn.cmd -B validate"
        cmd //c "cd /d $W && mvn.cmd -B validate" 2>&1 | grep -E '\[probe-|ERROR|WARN|BUILD' | sed 's/^/    /'
    done
}

run "" ""
run jvm.config   '-Dmaven.ext.class.path=.mvn/ext/probe-ext.jar'
run maven.config '-Dmaven.ext.class.path=.mvn/ext/probe-ext.jar'
run jvm.config   '-Dmaven.ext.class.path=${maven.multiModuleProjectDirectory}/.mvn/ext/probe-ext.jar'
run maven.config '-Dmaven.ext.class.path=${maven.multiModuleProjectDirectory}/.mvn/ext/probe-ext.jar'
run jvm.config   '-Dmaven.ext.class.path=${MAVEN_PROJECTBASEDIR}/.mvn/ext/probe-ext.jar'
run jvm.config   '-Dmaven.ext.class.path=!MAVEN_PROJECTBASEDIR!/.mvn/ext/probe-ext.jar'
rm -f .mvn/jvm.config .mvn/maven.config
