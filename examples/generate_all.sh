#!/bin/bash -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export NON_INTERACTIVE=1

echo "=========================================="
echo " Generating traces for all examples..."
echo "=========================================="

for dir in "${SCRIPT_DIR}"/example*; do
    if [ -d "${dir}" ] && [ -f "${dir}/test.sh" ]; then
        echo ""
        echo "--> Processing $(basename "${dir}")..."
        (
            cd "${dir}"
            ./test.sh
        )
    fi
done

echo ""
echo "=========================================="
echo " All traces generated successfully!"
echo "=========================================="
