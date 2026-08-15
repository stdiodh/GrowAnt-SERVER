#!/usr/bin/env bash

set -euo pipefail

for variable_name in RUN_ID BASE_URL TICKERS RECENT_FROM RECENT_TO DAY_FROM DAY_TO WEEK_FROM WEEK_TO; do
    if [[ -z "${!variable_name:-}" ]]; then
        echo "${variable_name} environment variable is required" >&2
        exit 2
    fi
done

for executable_name in git jq k6 shasum; do
    if ! command -v "${executable_name}" >/dev/null 2>&1; then
        echo "${executable_name} is required to run the B2/B3 market candles load test" >&2
        exit 127
    fi
done

if [[ ! "${RUN_ID}" =~ ^[A-Za-z0-9._-]+$ ]]; then
    echo "RUN_ID may contain only letters, numbers, '.', '_' and '-'" >&2
    exit 2
fi

base_url_normalized="${BASE_URL%/}"
base_url_lower="$(printf '%s' "${base_url_normalized}" | tr '[:upper:]' '[:lower:]')"
if [[ ! "${base_url_lower}" =~ ^https?://[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:[0-9]{1,5})?$ ]]; then
    echo "BASE_URL must be an HTTP(S) origin without credentials, path, query or fragment" >&2
    exit 2
fi
export BASE_URL="${base_url_normalized}"

case "${base_url_lower}" in
    *koreainvestment.com*|*tossinvest.com*|*kiwoom.com*|*ls-sec.co.kr*|*koscom.co.kr*|*krx.co.kr*)
        echo "B2/B3 must target GrowAnt, not a market-data provider endpoint" >&2
        exit 2
        ;;
esac

case "${base_url_lower}" in
    http://127.0.0.1|https://127.0.0.1|http://127.0.0.1:*|https://127.0.0.1:*|http://localhost|https://localhost|http://localhost:*|https://localhost:*)
        ;;
    *)
        if [[ "${ALLOW_NON_LOCAL_TARGET:-false}" != "true" ]]; then
            echo "Set ALLOW_NON_LOCAL_TARGET=true only for an authorized GrowAnt test environment" >&2
            exit 2
        fi
        ;;
esac

if env | awk -F= 'BEGIN { found = 0 } $1 ~ /^K6_/ { found = 1 } END { exit found ? 0 : 1 }'; then
    echo "K6_* environment variables are not allowed because they can change execution or expose credentials" >&2
    exit 2
fi

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
report_directory="${repository_root}/build/reports/market-data/${RUN_ID}"
manifest_path="${report_directory}/b2-load-manifest.json"
summary_path="${report_directory}/b2-rest-candles-k6-summary.json"
k6_config_path="${report_directory}/b2-k6-config.json"

if [[ -e "${report_directory}" ]]; then
    echo "Report directory already exists: ${report_directory}" >&2
    echo "Use a new RUN_ID so previous evidence is not overwritten" >&2
    exit 2
fi

export START_RATE="${START_RATE:-10}"
export PRE_ALLOCATED_VUS="${PRE_ALLOCATED_VUS:-100}"
export MAX_VUS="${MAX_VUS:-500}"
export P95_LIMIT_MS="${P95_LIMIT_MS:-200}"
export ERROR_RATE_LIMIT="${ERROR_RATE_LIMIT:-0.01}"
export CHECK_RATE_LIMIT="${CHECK_RATE_LIMIT:-0.99}"
export MIN_TOTAL_REQUESTS="${MIN_TOTAL_REQUESTS:-100}"
export MIN_RANGE_REQUESTS="${MIN_RANGE_REQUESTS:-1}"
export RECENT_MIN_CANDLES="${RECENT_MIN_CANDLES:-48}"
export DAY_MIN_CANDLES="${DAY_MIN_CANDLES:-390}"
export WEEK_MIN_CANDLES="${WEEK_MIN_CANDLES:-1950}"
export TICKER_DISTRIBUTION="${TICKER_DISTRIBUTION:-uniform}"
default_stages_json='[{"duration":"2m","target":25},{"duration":"3m","target":50},{"duration":"3m","target":100},{"duration":"2m","target":0}]'
export STAGES_JSON="${STAGES_JSON:-${default_stages_json}}"

if ! printf '%s' "${STAGES_JSON}" | jq -e '
    type == "array" and
    length > 0 and
    all(.[]; type == "object" and (.duration | type == "string") and (.target | type == "number" and floor == . and . >= 0))
' >/dev/null; then
    echo "STAGES_JSON must contain duration strings and non-negative integer targets" >&2
    exit 2
fi

ticker_count="$(printf '%s' "${TICKERS}" | tr ',' '\n' | awk 'NF { count += 1 } END { print count + 0 }')"
export EXPECTED_TICKER_COUNT="${EXPECTED_TICKER_COUNT:-${ticker_count}}"
ticker_checksum="$(printf '%s' "${TICKERS}" | shasum -a 256 | awk '{print $1}')"
script_checksum="$(shasum -a 256 "${repository_root}/scripts/market-candles-b2-load.js" | awk '{print $1}')"
wrapper_checksum="$(shasum -a 256 "${repository_root}/scripts/market-candles-b2-load.sh" | awk '{print $1}')"
git_commit="$(git -C "${repository_root}" rev-parse HEAD)"
git_dirty=false
if [[ -n "$(git -C "${repository_root}" status --porcelain)" ]]; then
    git_dirty=true
fi
token_configured=false
if [[ -n "${TOKEN:-}" ]]; then
    token_configured=true
fi

mkdir -p "${report_directory}"
jq -n '{}' > "${k6_config_path}"

jq -n \
    --arg startedAt "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
    --arg gitCommit "${git_commit}" \
    --arg baseUrl "${BASE_URL}" \
    --arg tickerChecksum "${ticker_checksum}" \
    --arg scriptChecksum "${script_checksum}" \
    --arg wrapperChecksum "${wrapper_checksum}" \
    --arg tickerDistribution "${TICKER_DISTRIBUTION}" \
    --arg recentFrom "${RECENT_FROM}" \
    --arg recentTo "${RECENT_TO}" \
    --arg dayFrom "${DAY_FROM}" \
    --arg dayTo "${DAY_TO}" \
    --arg weekFrom "${WEEK_FROM}" \
    --arg weekTo "${WEEK_TO}" \
    --arg k6Version "$(k6 version | head -n 1)" \
    --argjson gitDirty "${git_dirty}" \
    --argjson tickerCount "${ticker_count}" \
    --argjson expectedTickerCount "${EXPECTED_TICKER_COUNT}" \
    --argjson tokenConfigured "${token_configured}" \
    --argjson startRate "${START_RATE}" \
    --argjson preAllocatedVUs "${PRE_ALLOCATED_VUS}" \
    --argjson maxVUs "${MAX_VUS}" \
    --argjson p95LimitMs "${P95_LIMIT_MS}" \
    --argjson errorRateLimit "${ERROR_RATE_LIMIT}" \
    --argjson checkRateLimit "${CHECK_RATE_LIMIT}" \
    --argjson minimumTotalRequests "${MIN_TOTAL_REQUESTS}" \
    --argjson minimumRangeRequests "${MIN_RANGE_REQUESTS}" \
    --argjson recentMinimumCandles "${RECENT_MIN_CANDLES}" \
    --argjson dayMinimumCandles "${DAY_MIN_CANDLES}" \
    --argjson weekMinimumCandles "${WEEK_MIN_CANDLES}" \
    --argjson stages "${STAGES_JSON}" \
    '{
        startedAt: $startedAt,
        gitCommit: $gitCommit,
        gitDirty: $gitDirty,
        target: $baseUrl,
        tickerCount: $tickerCount,
        expectedTickerCount: $expectedTickerCount,
        tickerChecksum: $tickerChecksum,
        tickerDistribution: $tickerDistribution,
        tokenConfigured: $tokenConfigured,
        scripts: {
            load: { path: "scripts/market-candles-b2-load.js", sha256: $scriptChecksum },
            wrapper: { path: "scripts/market-candles-b2-load.sh", sha256: $wrapperChecksum }
        },
        ranges: {
            recent: { from: $recentFrom, to: $recentTo, weightPercent: 70, minimumCandles: $recentMinimumCandles },
            day: { from: $dayFrom, to: $dayTo, weightPercent: 25, minimumCandles: $dayMinimumCandles },
            week: { from: $weekFrom, to: $weekTo, weightPercent: 5, minimumCandles: $weekMinimumCandles }
        },
        load: {
            executor: "ramping-arrival-rate",
            k6Config: "b2-k6-config.json",
            maxRedirects: 0,
            startRate: $startRate,
            preAllocatedVUs: $preAllocatedVUs,
            maxVUs: $maxVUs,
            p95LimitMs: $p95LimitMs,
            errorRateLimit: $errorRateLimit,
            checkRateLimit: $checkRateLimit,
            minimumTotalRequests: $minimumTotalRequests,
            minimumRangeRequests: $minimumRangeRequests,
            stages: $stages
        },
        k6Version: $k6Version
    }' > "${manifest_path}"

set +e
(
    cd "${repository_root}"
    k6 run \
        --config "${k6_config_path}" \
        --max-redirects 0 \
        --summary-export "${summary_path}" \
        "scripts/market-candles-b2-load.js" 2>&1 | tee "${report_directory}/b2-k6.log"
)
k6_exit_code=$?
set -e

summary_written=false
if [[ -f "${summary_path}" ]]; then
    summary_written=true
fi

jq \
    --arg finishedAt "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
    --argjson k6ExitCode "${k6_exit_code}" \
    --argjson summaryWritten "${summary_written}" \
    '. + {finishedAt: $finishedAt, k6ExitCode: $k6ExitCode, summaryWritten: $summaryWritten}' \
    "${manifest_path}" > "${manifest_path}.tmp"
mv "${manifest_path}.tmp" "${manifest_path}"

echo "B2/B3 manifest: ${manifest_path}"
echo "B2/B3 k6 log: ${report_directory}/b2-k6.log"
if [[ "${summary_written}" == "true" ]]; then
    echo "B2/B3 k6 summary: ${summary_path}"
else
    echo "B2/B3 k6 summary was not created; inspect the k6 log" >&2
fi
exit "${k6_exit_code}"
