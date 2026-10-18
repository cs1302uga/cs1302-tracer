#!/bin/sh
# Accept only complete, successful results from every required CI group.
set -eu

failed=0

check_result() {
    if [ "$2" != success ]; then
        printf '%s did not succeed (result: %s).\n' "$1" "${2:-missing}" >&2
        failed=1
    fi
}

check_result 'Java' "${JAVA_RESULT-}"
check_result 'Documentation' "${DOCUMENTATION_RESULT-}"
check_result 'Scripts quality' "${SCRIPTS_QUALITY_RESULT-}"
check_result 'Windows packaging and launchers' "${WINDOWS_WRAPPER_RESULT-}"

if [ "$failed" -ne 0 ]; then
    exit 1
fi

printf '%s\n' 'All regression gate groups succeeded.'
