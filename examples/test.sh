#!/bin/bash -e

INPUT_FILE="${1}"
shift || true
TRACE_OPTIONS=("$@")

if [ -z "${INPUT_FILE}" ]; then
    echo "Usage: $0 <input_java_file> [trace_options...]"
    exit 1
fi

TRACE_FILE="${INPUT_FILE}.json"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
JAR_FILE="${ROOT_DIR}/target/code-tracer-jar-with-dependencies.jar"

# Build JAR if missing
if [ ! -f "${JAR_FILE}" ]; then
    echo "==> Building code-tracer fat JAR..."
    (
        cd "${ROOT_DIR}"
        mvn package -DskipTests -Djacoco.skip=true -q
    )
fi

echo "==> Generating trace for ${INPUT_FILE} -> ${TRACE_FILE}..."
(
    set -x
    java -jar "${JAR_FILE}" trace "${TRACE_OPTIONS[@]}" < "${INPUT_FILE}" > "${TRACE_FILE}"
)
echo "==> Successfully generated ${TRACE_FILE} ($(wc -c < "${TRACE_FILE}" | tr -d ' ') bytes)"

# Interactive prompt if running inside an interactive terminal
if [ -t 0 ] && [ -z "${NON_INTERACTIVE}" ]; then
    echo "Do you want to view ${TRACE_FILE}?"
    select yn in "Yes" "No"; do
        case ${yn} in
            Yes )
                if command -v jq >/dev/null 2>&1; then
                    jq . "${TRACE_FILE}"
                else
                    cat "${TRACE_FILE}"
                fi
                break
                ;;
            No )
                break
                ;;
        esac
    done

    echo "Do you want to delete ${TRACE_FILE}?"
    select yn in "Yes" "No"; do
        case ${yn} in
            Yes )
                (
                    set -x
                    rm -f "${TRACE_FILE}"
                )
                break
                ;;
            No )
                break
                ;;
        esac
    done
fi
