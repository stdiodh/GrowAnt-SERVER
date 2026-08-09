#!/usr/bin/env bash

set -euo pipefail

for variable_name in BASE_URL TICKER FROM TO; do
    if [[ -z "${!variable_name:-}" ]]; then
        echo "${variable_name} environment variable is required" >&2
        exit 2
    fi
done

if ! command -v k6 >/dev/null 2>&1; then
    echo "k6 is required to run the market candles load test" >&2
    exit 127
fi

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
report_directory="${repository_root}/build/reports/market-data"

mkdir -p "${report_directory}"

cd "${repository_root}"
k6 run \
    --summary-export "${report_directory}/rest-candles-k6-summary.json" \
    "scripts/market-candles-load.js"
