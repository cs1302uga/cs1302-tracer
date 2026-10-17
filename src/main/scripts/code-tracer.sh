#!/bin/bash
# Resolve the installed launcher without changing the caller's working directory.
set -e
launcher=${BASH_SOURCE[0]}
if [[ "$launcher" != */* ]]; then
    launcher=$(command -v -- "$launcher")
fi
links=0
while [[ -L "$launcher" ]]; do
    if (( links >= 40 )); then
        printf '%s\n' 'code-tracer: too many launcher symbolic links.' >&2
        exit 1
    fi
    script_dir=$(CDPATH='' cd -P -- "$(dirname -- "$launcher")" && pwd)
    launcher=$(readlink -- "$launcher")
    if [[ "$launcher" != /* ]]; then
        launcher=$script_dir/$launcher
    fi
    links=$((links + 1))
done
script_dir=$(CDPATH='' cd -P -- "$(dirname -- "$launcher")" && pwd)
# A nonempty override is authoritative; relative paths retain the caller's base.
jar=${CODE_TRACER_JAR_DIR:-$script_dir}/code-tracer.jar
if [[ ! -f "$jar" || ! -r "$jar" ]]; then
    if [[ -n "${CODE_TRACER_JAR_DIR:-}" ]]; then
        printf 'code-tracer: cannot read %s; correct CODE_TRACER_JAR_DIR.\n' "$jar" >&2
    else
        printf 'code-tracer: cannot read %s; keep the launcher beside code-tracer.jar or set CODE_TRACER_JAR_DIR.\n' "$jar" >&2
    fi
    exit 1
fi

# Honor an explicit JDK selection; otherwise use the caller's PATH.
if [[ -n "${JAVA_HOME:-}" ]]; then
    java_command=$JAVA_HOME/bin/java
    if [[ ! -x "$java_command" || -d "$java_command" ]]; then
        printf 'code-tracer: JAVA_HOME does not select an executable Java: %s\n' "$java_command" >&2
        exit 1
    fi
else
    java_command=$(command -v java || true)
    if [[ -z "$java_command" ]]; then
        printf '%s\n' 'code-tracer: Java was not found; set JAVA_HOME or add a full JDK 21 or later to PATH.' >&2
        exit 1
    fi
fi

# Require the compiler from the same selection source before starting the JVM.
if [[ -n "${JAVA_HOME:-}" ]]; then
    javac_command=$JAVA_HOME/bin/javac
else
    javac_command=$(command -v javac || true)
fi
if [[ ! -f "$javac_command" || ! -x "$javac_command" ]]; then
    printf 'code-tracer: no executable javac selected (%s); set JAVA_HOME to a full stable JDK 21 or later, or correct PATH.\n' "${javac_command:-not found on PATH}" >&2
    exit 1
fi

# Preserve arguments, standard streams, signals, and the Java process exit status.
exec "$java_command" -jar "$jar" "$@"
