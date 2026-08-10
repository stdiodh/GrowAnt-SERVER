#!/usr/bin/env bash

set -euo pipefail

readonly BLOG_EVIDENCE_SOURCE="blog-local-seed"
readonly BLOG_DEFAULT_START_DATE="2026-08-03"

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
blog_db_host="${BLOG_DB_HOST:-127.0.0.1}"
blog_db_port="${BLOG_DB_PORT:-5432}"
blog_db_name="${BLOG_DB_NAME:-growant}"
blog_db_user="${BLOG_DB_USER:-growant}"
blog_db_password="${BLOG_DB_PASSWORD:-growant}"
blog_api_base_url="${BLOG_API_BASE_URL:-http://127.0.0.1:8080}"
blog_seed_start_date="${BLOG_SEED_START_DATE:-${BLOG_DEFAULT_START_DATE}}"
blog_api_from="${BLOG_API_FROM:-${blog_seed_start_date}T09:00:00+09:00}"
blog_api_to="${BLOG_API_TO:-${blog_seed_start_date}T15:30:00+09:00}"

for required_command in curl jq; do
    if ! command -v "${required_command}" >/dev/null 2>&1; then
        echo "${required_command} is required" >&2
        exit 127
    fi
done

run_blog_psql() {
    if command -v psql >/dev/null 2>&1; then
        PGPASSWORD="${blog_db_password}" psql \
            --no-psqlrc \
            --set ON_ERROR_STOP=1 \
            --host "${blog_db_host}" \
            --port "${blog_db_port}" \
            --username "${blog_db_user}" \
            --dbname "${blog_db_name}" \
            "$@"
        return
    fi

    if ! command -v docker >/dev/null 2>&1; then
        echo "psql or Docker Compose is required" >&2
        exit 127
    fi

    if ! docker compose --project-directory "${repository_root}" ps --status running --services | grep -qx postgres; then
        echo "PostgreSQL is not running. Start it with: docker compose up -d postgres" >&2
        exit 1
    fi

    docker compose --project-directory "${repository_root}" exec -T postgres \
        psql \
        --no-psqlrc \
        --set ON_ERROR_STOP=1 \
        --username "${blog_db_user}" \
        --dbname "${blog_db_name}" \
        "$@"
}

temporary_directory="$(mktemp -d)"
trap 'rm -rf -- "${temporary_directory}"' EXIT

api_response_file="${temporary_directory}/candles.json"
login_response_file="${temporary_directory}/login.json"
curl_auth_config="${temporary_directory}/curl-auth.conf"
blog_api_token="${BLOG_API_TOKEN:-}"

if [[ -z "${blog_api_token}" ]]; then
    curl \
        --silent \
        --show-error \
        --fail-with-body \
        --header 'Content-Type: application/json' \
        --data '{"provider":"kakao","nickname":"blog-local-evidence"}' \
        --output "${login_response_file}" \
        "${blog_api_base_url%/}/api/auth/login"
    blog_api_token="$(jq -er '.data.token' "${login_response_file}")"
fi

printf 'header = "Authorization: Bearer %s"\n' "${blog_api_token}" >"${curl_auth_config}"

curl \
    --silent \
    --show-error \
    --fail-with-body \
    --get \
    --config "${curl_auth_config}" \
    --header 'Accept: application/json' \
    --data-urlencode "from=${blog_api_from}" \
    --data-urlencode "to=${blog_api_to}" \
    --output "${api_response_file}" \
    "${blog_api_base_url%/}/api/market/005930/candles"

if ! jq -e '
    .success == true
    and .data.ticker == "005930"
    and .data.interval == "1m"
    and (.data.candles | type == "array" and length > 0)
' "${api_response_file}" >/dev/null; then
    echo "The candle API response does not match the expected contract" >&2
    exit 1
fi

db_summary_rows="$(run_blog_psql \
    --tuples-only \
    --no-align \
    --field-separator '|' \
    --set "evidence_source=${BLOG_EVIDENCE_SOURCE}" \
    --set "evidence_from=${blog_api_from}" \
    --set "evidence_to=${blog_api_to}" <<'SQL'
SELECT
    ticker,
    COUNT(*),
    TO_CHAR(MIN(bucket_start) AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI'),
    TO_CHAR(MAX(bucket_start) AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI')
FROM minute_candles
WHERE source = :'evidence_source'
  AND bucket_start >= :'evidence_from'::TIMESTAMPTZ
  AND bucket_start < :'evidence_to'::TIMESTAMPTZ
GROUP BY ticker
ORDER BY ticker;
SQL
)"

db_api_ticker_count=""
while IFS='|' read -r ticker row_count _; do
    if [[ "${ticker}" == "005930" ]]; then
        db_api_ticker_count="${row_count}"
        break
    fi
done <<<"${db_summary_rows}"

api_candle_count="$(jq -r '.data.candles | length' "${api_response_file}")"
if [[ -z "${db_api_ticker_count}" || "${api_candle_count}" != "${db_api_ticker_count}" ]]; then
    echo "The API and DB candle counts do not match" >&2
    exit 1
fi

db_last_candle="$(run_blog_psql \
    --tuples-only \
    --no-align \
    --set "evidence_source=${BLOG_EVIDENCE_SOURCE}" \
    --set "evidence_from=${blog_api_from}" \
    --set "evidence_to=${blog_api_to}" <<'SQL'
SELECT JSON_BUILD_OBJECT(
    'time', TO_CHAR(bucket_start AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD"T"HH24:MI:SS') || '+09:00',
    'open', open,
    'high', high,
    'low', low,
    'close', close,
    'volume', volume,
    'tradeCount', trade_count,
    'final', is_final,
    'revision', revision
)::TEXT
FROM minute_candles
WHERE ticker = '005930'
  AND source = :'evidence_source'
  AND bucket_start >= :'evidence_from'::TIMESTAMPTZ
  AND bucket_start < :'evidence_to'::TIMESTAMPTZ
ORDER BY bucket_start DESC
LIMIT 1;
SQL
)"

if [[ -z "${db_last_candle}" ]]; then
    echo "No blog-local-seed candle was found in the requested DB range" >&2
    exit 1
fi

api_last_values="$(jq -c '.data.candles[-1] | {
    open,
    high,
    low,
    close,
    volume,
    tradeCount,
    final,
    revision
}' "${api_response_file}")"
db_last_values="$(jq -c '{
    open,
    high,
    low,
    close,
    volume,
    tradeCount,
    final,
    revision
}' <<<"${db_last_candle}")"

if ! jq -en \
    --argjson api "${api_last_values}" \
    --argjson db "${db_last_values}" \
    '$api == $db' >/dev/null; then
    echo "The API and DB last-candle values do not match" >&2
    exit 1
fi

api_summary="$(jq -c '{
    ticker: .data.ticker,
    interval: .data.interval,
    timezone: .data.timezone,
    candleCount: (.data.candles | length),
    firstTime: .data.candles[0].time,
    lastTime: .data.candles[-1].time,
    lastCandle: (.data.candles[-1] | {
        open,
        high,
        low,
        close,
        volume,
        tradeCount,
        final,
        revision
    })
}' "${api_response_file}")"

report_directory="${repository_root}/build/reports/market-data"
report_file="${report_directory}/blog-local-evidence.md"
mkdir -p "${report_directory}"

{
    echo "# Blog local minute-candle evidence"
    echo
    echo "- Generated at: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    echo "- Commit: $(git -C "${repository_root}" rev-parse --short HEAD)"
    echo "- Source: ${BLOG_EVIDENCE_SOURCE}"
    echo "- API range: ${blog_api_from} (inclusive) to ${blog_api_to} (exclusive)"
    echo "- Authentication: local demo token used but intentionally omitted"
    echo
    echo "## Database rows in the API range"
    echo
    echo "| Ticker | Rows | First candle (KST) | Last candle (KST) |"
    echo "| --- | ---: | --- | --- |"
    while IFS='|' read -r ticker row_count first_candle last_candle; do
        [[ -z "${ticker}" ]] && continue
        echo "| ${ticker} | ${row_count} | ${first_candle} | ${last_candle} |"
    done <<<"${db_summary_rows}"
    echo
    echo "## API summary"
    echo
    echo '```json'
    jq . <<<"${api_summary}"
    echo '```'
    echo
    echo "## API and DB consistency"
    echo
    echo "- Result: PASS"
    echo "- Compared count: ${api_candle_count} candles"
    echo "- Compared fields: open, high, low, close, volume, tradeCount, final, revision"
    echo "- DB last time: $(jq -r '.time' <<<"${db_last_candle}")"
    echo "- API last time: $(jq -r '.data.candles[-1].time' "${api_response_file}")"
    echo "- Secrets and JWT values are not included in this report."
} | tee "${report_file}"

echo
echo "Evidence report: ${report_file}"
