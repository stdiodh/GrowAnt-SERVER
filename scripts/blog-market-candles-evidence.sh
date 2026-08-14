#!/usr/bin/env bash

set -euo pipefail

# This evidence path intentionally validates the five-ticker catalog-week seed.

readonly BLOG_EVIDENCE_SOURCE="blog-local-seed"
readonly BLOG_DEFAULT_START_DATE="2026-08-03"
readonly BLOG_EXPECTED_TICKERS="000660,005380,005930,035420,035720"

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
blog_db_host="${BLOG_DB_HOST:-127.0.0.1}"
blog_db_port="${BLOG_DB_PORT:-5432}"
blog_db_name="${BLOG_DB_NAME:-growant}"
blog_db_user="${BLOG_DB_USER:-growant}"
blog_db_password="${BLOG_DB_PASSWORD:-growant}"
blog_api_base_url="${BLOG_API_BASE_URL:-http://127.0.0.1:8080}"
blog_seed_start_date="${BLOG_SEED_START_DATE:-${BLOG_DEFAULT_START_DATE}}"
blog_api_from="${BLOG_API_FROM:-${blog_seed_start_date}T09:00:00+09:00}"
blog_api_to="${BLOG_API_TO:-}"

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

if [[ -z "${blog_api_to}" ]]; then
    blog_default_end_date="$(run_blog_psql \
        --tuples-only \
        --no-align \
        --set "seed_start_date=${blog_seed_start_date}" <<'SQL'
SELECT (:'seed_start_date'::DATE + 5)::TEXT;
SQL
)"
    blog_api_to="${blog_default_end_date}T09:00:00+09:00"
fi

temporary_directory="$(mktemp -d)"
trap 'rm -rf -- "${temporary_directory}"' EXIT

api_response_file="${temporary_directory}/candles.json"
db_candles_file="${temporary_directory}/db-candles.json"
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

expected_range_summary="$(run_blog_psql \
    --tuples-only \
    --no-align \
    --field-separator '|' \
    --set "evidence_from=${blog_api_from}" \
    --set "evidence_to=${blog_api_to}" <<'SQL'
WITH bounds AS (
    SELECT
        :'evidence_from'::TIMESTAMPTZ AS from_inclusive,
        :'evidence_to'::TIMESTAMPTZ AS to_exclusive
),
calendar_days AS (
    SELECT trade_date::DATE AS trade_date
    FROM bounds
    CROSS JOIN LATERAL GENERATE_SERIES(
        (from_inclusive AT TIME ZONE 'Asia/Seoul')::DATE,
        ((to_exclusive - INTERVAL '1 microsecond') AT TIME ZONE 'Asia/Seoul')::DATE,
        INTERVAL '1 day'
    ) AS days(trade_date)
    WHERE EXTRACT(ISODOW FROM trade_date) BETWEEN 1 AND 5
),
generated_minutes AS (
    SELECT
        ((trade_date + TIME '09:00') + minute_index * INTERVAL '1 minute')
            AT TIME ZONE 'Asia/Seoul' AS bucket_start
    FROM calendar_days
    CROSS JOIN GENERATE_SERIES(0, 389) AS minutes(minute_index)
),
expected_minutes AS (
    SELECT bucket_start
    FROM generated_minutes
    CROSS JOIN bounds
    WHERE bucket_start >= from_inclusive
      AND bucket_start < to_exclusive
)
SELECT
    COUNT(*),
    COUNT(DISTINCT (bucket_start AT TIME ZONE 'Asia/Seoul')::DATE),
    TO_CHAR(MIN(bucket_start) AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD"T"HH24:MI:SS') || '+09:00',
    TO_CHAR(MAX(bucket_start) AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD"T"HH24:MI:SS') || '+09:00'
FROM expected_minutes;
SQL
)"

IFS='|' read -r \
    expected_candle_count \
    expected_session_count \
    expected_first_candle \
    expected_last_candle <<<"${expected_range_summary}"

if (( expected_candle_count == 0 || expected_session_count == 0 )); then
    echo "The API range must include at least one regular-market minute" >&2
    exit 1
fi
expected_quality_pair_count=$((expected_candle_count - expected_session_count))

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
    TO_CHAR(MIN(bucket_start) AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD"T"HH24:MI:SS') || '+09:00',
    TO_CHAR(MAX(bucket_start) AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD"T"HH24:MI:SS') || '+09:00'
FROM minute_candles
WHERE source = :'evidence_source'
  AND bucket_start >= :'evidence_from'::TIMESTAMPTZ
  AND bucket_start < :'evidence_to'::TIMESTAMPTZ
GROUP BY ticker
ORDER BY ticker;
SQL
)"

summary_check_failed=0
actual_ticker_count=0
actual_tickers=""
while IFS='|' read -r ticker row_count first_candle last_candle; do
    [[ -z "${ticker}" ]] && continue
    actual_ticker_count=$((actual_ticker_count + 1))
    actual_tickers="${actual_tickers}${actual_tickers:+,}${ticker}"

    if [[ "${row_count}" != "${expected_candle_count}" ]]; then
        echo "Unexpected regular-market row count for ${ticker}: ${row_count}/${expected_candle_count}" >&2
        summary_check_failed=1
    fi
    if [[ "${first_candle}" != "${expected_first_candle}" ]]; then
        echo "Unexpected first regular-market candle for ${ticker}: ${first_candle}" >&2
        summary_check_failed=1
    fi
    if [[ "${last_candle}" != "${expected_last_candle}" ]]; then
        echo "Unexpected last regular-market candle for ${ticker}: ${last_candle}" >&2
        summary_check_failed=1
    fi
done <<<"${db_summary_rows}"

if (( actual_ticker_count != 5 )) || [[ "${actual_tickers}" != "${BLOG_EXPECTED_TICKERS}" ]]; then
    echo "Unexpected ticker set: ${actual_tickers:-none}; evidence expects the catalog-week seed" >&2
    summary_check_failed=1
fi

if (( summary_check_failed != 0 )); then
    exit 1
fi

db_quality_rows="$(run_blog_psql \
    --tuples-only \
    --no-align \
    --field-separator '|' \
    --set "evidence_source=${BLOG_EVIDENCE_SOURCE}" \
    --set "evidence_from=${blog_api_from}" \
    --set "evidence_to=${blog_api_to}" <<'SQL'
WITH catalog(ticker, base_price, price_unit) AS (
    VALUES
        ('005930',  76300, 100),
        ('000660', 178500, 100),
        ('035720',  41200,  50),
        ('035420', 198400, 100),
        ('005380', 247000, 500)
),
ordered_candles AS (
    SELECT
        minute_candles.ticker,
        catalog.base_price,
        catalog.price_unit,
        bucket_start,
        open,
        high,
        low,
        close,
        volume,
        trade_count,
        (bucket_start AT TIME ZONE 'Asia/Seoul')::DATE AS trade_date,
        SUM(close - open) OVER (
            PARTITION BY ticker, (bucket_start AT TIME ZONE 'Asia/Seoul')::DATE
        ) AS daily_movement_sum,
        LAG(bucket_start) OVER (
            PARTITION BY ticker, (bucket_start AT TIME ZONE 'Asia/Seoul')::DATE
            ORDER BY bucket_start
        ) AS previous_bucket_start,
        LAG(close) OVER (
            PARTITION BY ticker, (bucket_start AT TIME ZONE 'Asia/Seoul')::DATE
            ORDER BY bucket_start
        ) AS previous_close
    FROM minute_candles
    JOIN catalog USING (ticker)
    WHERE source = :'evidence_source'
      AND bucket_start >= :'evidence_from'::TIMESTAMPTZ
      AND bucket_start < :'evidence_to'::TIMESTAMPTZ
)
SELECT
    ticker,
    MAX(base_price) AS target_close,
    (ARRAY_AGG(close ORDER BY bucket_start DESC))[1] AS last_close,
    MAX(price_unit) AS price_unit,
    COUNT(*) AS row_count,
    COUNT(previous_close) AS continuity_pair_count,
    COUNT(*) FILTER (WHERE previous_close IS NOT NULL AND open = previous_close)
        AS continuous_pair_count,
    COALESCE(ROUND(AVG(ABS(open - previous_close))::NUMERIC, 3), 0)
        AS mean_absolute_gap,
    COALESCE(MAX(ABS(open - previous_close)), 0) AS max_absolute_gap,
    COUNT(previous_bucket_start) AS interval_pair_count,
    COUNT(*) FILTER (
        WHERE previous_bucket_start IS NOT NULL
          AND bucket_start - previous_bucket_start = INTERVAL '1 minute'
    ) AS one_minute_interval_count,
    COUNT(*) FILTER (
        WHERE open <= 0
           OR high <= 0
           OR low <= 0
           OR close <= 0
           OR volume <= 0
           OR trade_count <= 0
           OR high < GREATEST(open, close)
           OR low > LEAST(open, close)
           OR high < low
    ) AS invalid_ohlcv_count,
    COUNT(*) FILTER (
        WHERE open % price_unit <> 0
           OR high % price_unit <> 0
           OR low % price_unit <> 0
           OR close % price_unit <> 0
           OR CASE
                WHEN open < 2000 THEN 1
                WHEN open < 5000 THEN 5
                WHEN open < 20000 THEN 10
                WHEN open < 50000 THEN 50
                WHEN open < 200000 THEN 100
                WHEN open < 500000 THEN 500
                ELSE 1000
              END <> price_unit
           OR CASE
                WHEN high < 2000 THEN 1
                WHEN high < 5000 THEN 5
                WHEN high < 20000 THEN 10
                WHEN high < 50000 THEN 50
                WHEN high < 200000 THEN 100
                WHEN high < 500000 THEN 500
                ELSE 1000
              END <> price_unit
           OR CASE
                WHEN low < 2000 THEN 1
                WHEN low < 5000 THEN 5
                WHEN low < 20000 THEN 10
                WHEN low < 50000 THEN 50
                WHEN low < 200000 THEN 100
                WHEN low < 500000 THEN 500
                ELSE 1000
              END <> price_unit
           OR CASE
                WHEN close < 2000 THEN 1
                WHEN close < 5000 THEN 5
                WHEN close < 20000 THEN 10
                WHEN close < 50000 THEN 50
                WHEN close < 200000 THEN 100
                WHEN close < 500000 THEN 500
                ELSE 1000
              END <> price_unit
    ) AS tick_unit_violation_count,
    COALESCE(MAX(ABS(close - open) / price_unit), 0) AS max_movement_ticks,
    COUNT(DISTINCT trade_date) FILTER (WHERE daily_movement_sum <> 0)
        AS non_zero_daily_movement_count
FROM ordered_candles
GROUP BY ticker
ORDER BY ticker;
SQL
)"

quality_check_failed=0
while IFS='|' read -r \
    ticker \
    target_close \
    last_close \
    _price_unit \
    row_count \
    continuity_pair_count \
    continuous_pair_count \
    mean_absolute_gap \
    max_absolute_gap \
    interval_pair_count \
    one_minute_interval_count \
    invalid_ohlcv_count \
    tick_unit_violation_count \
    max_movement_ticks \
    non_zero_daily_movement_count; do
    [[ -z "${ticker}" ]] && continue

    if [[ "${last_close}" != "${target_close}" ]]; then
        echo "The last close does not match the catalog price for ${ticker}" >&2
        quality_check_failed=1
    fi
    if [[ "${row_count}" != "${expected_candle_count}" ]]; then
        echo "The quality row count is incomplete for ${ticker}" >&2
        quality_check_failed=1
    fi
    if [[ "${continuity_pair_count}" != "${expected_quality_pair_count}" ]] \
        || [[ "${continuous_pair_count}" != "${expected_quality_pair_count}" ]]; then
        echo "Intra-day open/previous-close continuity failed for ${ticker}" >&2
        quality_check_failed=1
    fi
    if [[ "${mean_absolute_gap}" != "0" && "${mean_absolute_gap}" != "0.000" ]] \
        || [[ "${max_absolute_gap}" != "0" ]]; then
        echo "A non-zero intra-day price gap was found for ${ticker}" >&2
        quality_check_failed=1
    fi
    if [[ "${interval_pair_count}" != "${expected_quality_pair_count}" ]] \
        || [[ "${one_minute_interval_count}" != "${expected_quality_pair_count}" ]]; then
        echo "A non-one-minute timestamp interval was found for ${ticker}" >&2
        quality_check_failed=1
    fi
    if [[ "${invalid_ohlcv_count}" != "0" ]]; then
        echo "Invalid OHLCV rows were found for ${ticker}" >&2
        quality_check_failed=1
    fi
    if [[ "${tick_unit_violation_count}" != "0" ]]; then
        echo "Price-unit violations were found for ${ticker}" >&2
        quality_check_failed=1
    fi
    if (( max_movement_ticks > 2 )); then
        echo "A movement larger than two ticks was found for ${ticker}" >&2
        quality_check_failed=1
    fi
    if [[ "${non_zero_daily_movement_count}" != "0" ]]; then
        echo "A trading day with a non-zero movement sum was found for ${ticker}" >&2
        quality_check_failed=1
    fi
done <<<"${db_quality_rows}"

if (( quality_check_failed != 0 )); then
    exit 1
fi

api_candle_count="$(jq -r '.data.candles | length' "${api_response_file}")"
if [[ "${api_candle_count}" != "${expected_candle_count}" ]]; then
    echo "The API candle count does not match the regular-market range" >&2
    exit 1
fi

run_blog_psql \
    --tuples-only \
    --no-align \
    --set "evidence_source=${BLOG_EVIDENCE_SOURCE}" \
    --set "evidence_from=${blog_api_from}" \
    --set "evidence_to=${blog_api_to}" <<'SQL' >"${db_candles_file}"
SELECT COALESCE(
    JSONB_AGG(
        JSONB_BUILD_OBJECT(
            'time', TO_CHAR(bucket_start AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD"T"HH24:MI:SS') || '+09:00',
            'open', open,
            'high', high,
            'low', low,
            'close', close,
            'volume', volume,
            'tradeCount', trade_count,
            'final', is_final,
            'revision', revision
        )
        ORDER BY bucket_start
    ),
    '[]'::JSONB
)::TEXT
FROM minute_candles
WHERE ticker = '005930'
  AND source = :'evidence_source'
  AND bucket_start >= :'evidence_from'::TIMESTAMPTZ
  AND bucket_start < :'evidence_to'::TIMESTAMPTZ;
SQL

if ! jq -e \
    --slurpfile database_candles "${db_candles_file}" \
    '.data.candles == $database_candles[0]' \
    "${api_response_file}" >/dev/null; then
    echo "The ordered API and DB candle arrays do not match" >&2
    exit 1
fi

db_last_candle="$(jq -c '.[-1]' "${db_candles_file}")"

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
    echo "- Expected tickers: ${BLOG_EXPECTED_TICKERS}"
    echo "- Expected regular-market candles per ticker: ${expected_candle_count}"
    echo "- Expected first/last candle: ${expected_first_candle} / ${expected_last_candle}"
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
    echo "## Database candle quality"
    echo
    echo "| Ticker | Unit | Last close / target | Rows | Open = previous close | Mean absolute gap | Max absolute gap | 1-minute intervals | Invalid OHLCV | Unit violations | Max move | Non-zero daily sums |"
    echo "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"
    while IFS='|' read -r \
        ticker \
        target_close \
        last_close \
        price_unit \
        row_count \
        continuity_pair_count \
        continuous_pair_count \
        mean_absolute_gap \
        max_absolute_gap \
        interval_pair_count \
        one_minute_interval_count \
        invalid_ohlcv_count \
        tick_unit_violation_count \
        max_movement_ticks \
        non_zero_daily_movement_count; do
        [[ -z "${ticker}" ]] && continue
        echo "| ${ticker} | ${price_unit} | ${last_close}/${target_close} | ${row_count} | ${continuous_pair_count}/${expected_quality_pair_count} | ${mean_absolute_gap} | ${max_absolute_gap} | ${one_minute_interval_count}/${expected_quality_pair_count} | ${invalid_ohlcv_count} | ${tick_unit_violation_count} | ${max_movement_ticks} ticks | ${non_zero_daily_movement_count} |"
    done <<<"${db_quality_rows}"
    echo
    echo "- Intra-day continuity uses PostgreSQL LAG(close), partitioned by ticker and KST trading date."
    echo "- Overnight gaps are intentionally excluded; each new trading day may start with a small deterministic gap."
    echo "- Result: PASS"
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
    echo "- Compared every ordered candle, not only the last candle."
    echo "- Compared fields: time, open, high, low, close, volume, tradeCount, final, revision"
    echo "- DB last time: $(jq -r '.time' <<<"${db_last_candle}")"
    echo "- API last time: $(jq -r '.data.candles[-1].time' "${api_response_file}")"
    echo "- Secrets and JWT values are not included in this report."
} | tee "${report_file}"

echo
echo "Evidence report: ${report_file}"
