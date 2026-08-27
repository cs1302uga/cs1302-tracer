#!/bin/bash -e

cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null

SCRIPT_DIR="$(pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
JAR_FILE="${ROOT_DIR}/target/code-tracer-jar-with-dependencies.jar"
TRACE_FILE="stream.json"

echo "==> Streaming multi-file packaged code via stdin into trace -a..."

cat << 'EOF' | java -jar "${JAR_FILE}" trace -a > "${TRACE_FILE}"
// --- cs1302/math/Calculator.java ---
package cs1302.math;

public class Calculator {

    public static int multiply(int a, int b) {
        int result = 0;
        for (int i = 0; i < b; i++) {
            result += a;
        } // for
        return result;
    } // multiply

} // Calculator

// --- cs1302/math/Driver.java ---
package cs1302.math;

public class Driver {

    public static void main(String[] args) {
        int x = 4;
        int y = 3;
        int product = Calculator.multiply(x, y);
        System.out.printf("%d * %d = %d%n", x, y, product);
    } // main

} // Driver
EOF

echo "==> Successfully generated ${TRACE_FILE} ($(wc -c < "${TRACE_FILE}" | tr -d ' ') bytes)"

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
                rm -f "${TRACE_FILE}"
                break
                ;;
            No )
                break
                ;;
        esac
    done
fi
