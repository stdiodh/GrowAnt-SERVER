#!/usr/bin/env bash

set -euo pipefail

readonly BLOG_DEFAULT_START_DATE="2026-08-03"

usage() {
    printf '%s\n' \
        "Usage: scripts/blog-market-candles-seed.sh [samsung-day|catalog-week]" \
        "" \
        "  samsung-day  Insert 390 candles for 005930 on one trading day (quick screen seed only)." \
        "  catalog-week Insert 9,750 candles for five catalog tickers over five trading days." \
        "               Use this scope with blog-market-candles-evidence.sh." \
        "" \
        "Optional environment variables:" \
        "  BLOG_SEED_START_DATE  First trading date (default: ${BLOG_DEFAULT_START_DATE})" \
        "  BLOG_DB_HOST          PostgreSQL host (default: 127.0.0.1)" \
        "  BLOG_DB_PORT          PostgreSQL port (default: 5432)" \
        "  BLOG_DB_NAME          Database name (default: growant)" \
        "  BLOG_DB_USER          Database user (default: growant)" \
        "  BLOG_DB_PASSWORD      Database password (default: growant)"
}

seed_scope="${1:-catalog-week}"
case "${seed_scope}" in
    samsung-day|catalog-week) ;;
    -h|--help)
        usage
        exit 0
        ;;
    *)
        usage >&2
        exit 2
        ;;
esac

seed_start_date="${BLOG_SEED_START_DATE:-${BLOG_DEFAULT_START_DATE}}"
if [[ ! "${seed_start_date}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
    echo "BLOG_SEED_START_DATE must use YYYY-MM-DD format" >&2
    exit 2
fi

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
blog_db_host="${BLOG_DB_HOST:-127.0.0.1}"
blog_db_port="${BLOG_DB_PORT:-5432}"
blog_db_name="${BLOG_DB_NAME:-growant}"
blog_db_user="${BLOG_DB_USER:-growant}"
blog_db_password="${BLOG_DB_PASSWORD:-growant}"

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

seed_start_day_of_week="$(run_blog_psql \
    --tuples-only \
    --no-align \
    --set "seed_start_date=${seed_start_date}" <<'SQL'
SELECT EXTRACT(ISODOW FROM :'seed_start_date'::DATE);
SQL
)"

if [[ "${seed_start_day_of_week}" != "1" ]]; then
    echo "BLOG_SEED_START_DATE must be a Monday so five trading days stay inside one API range" >&2
    exit 2
fi

run_blog_psql \
    --set "seed_scope=${seed_scope}" \
    --set "seed_start_date=${seed_start_date}" <<'SQL'
BEGIN;

CREATE TEMP TABLE blog_seed_expected (
    ticker             VARCHAR(10) NOT NULL,
    bucket_start       TIMESTAMPTZ NOT NULL,
    open               INTEGER NOT NULL,
    high               INTEGER NOT NULL,
    low                INTEGER NOT NULL,
    close              INTEGER NOT NULL,
    volume             BIGINT NOT NULL,
    trade_count        BIGINT NOT NULL,
    revision           INTEGER NOT NULL,
    is_final           BOOLEAN NOT NULL,
    source             VARCHAR(40) NOT NULL,
    source_updated_at  TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (ticker, bucket_start)
) ON COMMIT DROP;

WITH config AS (
    SELECT
        :'seed_scope'::TEXT AS seed_scope,
        :'seed_start_date'::DATE AS seed_start_date,
        CASE :'seed_scope'::TEXT WHEN 'samsung-day' THEN 1 ELSE 5 END AS trading_day_count
),
catalog(ticker, base_price, price_unit, ticker_order) AS (
    VALUES
        ('005930',  76300, 100, 0),
        ('000660', 178500, 100, 1),
        ('035720',  41200,  50, 2),
        ('035420', 198400, 100, 3),
        ('005380', 247000, 500, 4)
),
selected_catalog AS (
    SELECT catalog.*
    FROM catalog
    CROSS JOIN config
    WHERE config.seed_scope = 'catalog-week' OR catalog.ticker = '005930'
),
trading_days AS (
    SELECT
        config.seed_start_date + day_index AS trade_date,
        day_index
    FROM config
    CROSS JOIN LATERAL generate_series(0, config.trading_day_count - 1) AS days(day_index)
),
minute_inputs AS (
    SELECT
        selected_catalog.*,
        trading_days.trade_date,
        trading_days.day_index,
        minute_index,
        minute_index / 13 AS motion_block,
        minute_index % 13 AS motion_position
    FROM selected_catalog
    CROSS JOIN trading_days
    CROSS JOIN generate_series(0, 389) AS minutes(minute_index)
),
motion_slots AS (
    SELECT
        minute_inputs.*,
        (
            motion_position
                * ((motion_block + day_index * 3 + ticker_order * 5) % 12 + 1)
            + (motion_block * 7 + day_index * 11 + ticker_order * 17) % 13
        ) % 13 AS motion_slot
    FROM minute_inputs
),
price_movements AS (
    SELECT
        motion_slots.*,
        CASE
            WHEN motion_slot = 3 THEN 2
            WHEN motion_slot IN (0, 7, 10) THEN 1
            WHEN motion_slot IN (1, 4, 9) THEN -1
            WHEN motion_slot = 6 THEN -2
            ELSE 0
        END * price_unit AS close_delta,
        CASE
            WHEN minute_index = 0 AND day_index > 0
                THEN (((day_index * 3 + ticker_order * 2) % 5) - 2) * price_unit
            ELSE 0
        END AS session_gap
    FROM motion_slots
),
continuous_prices AS (
    SELECT
        price_movements.*,
        (
            base_price
            - SUM(close_delta) OVER (PARTITION BY ticker)
            - SUM(session_gap) OVER (PARTITION BY ticker)
            + COALESCE(
                SUM(close_delta) OVER (
                    PARTITION BY ticker
                    ORDER BY day_index, minute_index
                    ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
                ),
                0
            )
            + SUM(session_gap) OVER (
                PARTITION BY ticker
                ORDER BY day_index, minute_index
                ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
            )
        )::INTEGER AS open_price
    FROM price_movements
),
candles AS (
    SELECT
        ticker,
        ((trade_date + TIME '09:00') + minute_index * INTERVAL '1 minute')
            AT TIME ZONE 'Asia/Seoul' AS bucket_start,
        open_price AS open,
        GREATEST(open_price, open_price + close_delta)
            + ((minute_index + day_index + ticker_order) % 5 + 1) * price_unit AS high,
        LEAST(open_price, open_price + close_delta)
            - ((minute_index * 2 + day_index + ticker_order) % 5 + 1) * price_unit AS low,
        open_price + close_delta AS close,
        (
            1500
            + ticker_order * 300
            + day_index * 120
            + ABS(2 * minute_index - 389) * 12
            + (minute_index * 97 + day_index * 41 + ticker_order * 211) % 800
        )::BIGINT AS volume,
        (
            20
            + ABS(2 * minute_index - 389) / 20
            + (minute_index * 13 + day_index * 7 + ticker_order * 3) % 45
        )::BIGINT AS trade_count,
        1 AS revision,
        TRUE AS is_final,
        'blog-local-seed'::VARCHAR(40) AS source
    FROM continuous_prices
)
INSERT INTO blog_seed_expected (
    ticker,
    bucket_start,
    open,
    high,
    low,
    close,
    volume,
    trade_count,
    revision,
    is_final,
    source,
    source_updated_at,
    updated_at
)
SELECT
    ticker,
    bucket_start,
    open,
    high,
    low,
    close,
    volume,
    trade_count,
    revision,
    is_final,
    source,
    bucket_start + INTERVAL '6 hours 30 minutes',
    bucket_start + INTERVAL '6 hours 30 minutes'
FROM candles;

INSERT INTO minute_candles (
    ticker,
    bucket_start,
    open,
    high,
    low,
    close,
    volume,
    trade_count,
    revision,
    is_final,
    source,
    source_updated_at,
    updated_at
)
SELECT
    ticker,
    bucket_start,
    open,
    high,
    low,
    close,
    volume,
    trade_count,
    revision,
    is_final,
    source,
    source_updated_at,
    updated_at
FROM blog_seed_expected
ON CONFLICT (ticker, bucket_start) DO UPDATE SET
    open = EXCLUDED.open,
    high = EXCLUDED.high,
    low = EXCLUDED.low,
    close = EXCLUDED.close,
    volume = EXCLUDED.volume,
    trade_count = EXCLUDED.trade_count,
    revision = EXCLUDED.revision,
    is_final = EXCLUDED.is_final,
    source = EXCLUDED.source,
    source_updated_at = EXCLUDED.source_updated_at,
    updated_at = EXCLUDED.updated_at
WHERE minute_candles.source = 'blog-local-seed'
  AND ROW(
        minute_candles.open,
        minute_candles.high,
        minute_candles.low,
        minute_candles.close,
        minute_candles.volume,
        minute_candles.trade_count,
        minute_candles.revision,
        minute_candles.is_final,
        minute_candles.source
      ) IS DISTINCT FROM ROW(
        EXCLUDED.open,
        EXCLUDED.high,
        EXCLUDED.low,
        EXCLUDED.close,
        EXCLUDED.volume,
        EXCLUDED.trade_count,
        EXCLUDED.revision,
        EXCLUDED.is_final,
        EXCLUDED.source
      );

CREATE TEMP TABLE blog_seed_verification ON COMMIT DROP AS
SELECT
    COUNT(*) AS expected_count,
    COUNT(actual.ticker) FILTER (
        WHERE actual.source = expected.source
          AND ROW(
                actual.open,
                actual.high,
                actual.low,
                actual.close,
                actual.volume,
                actual.trade_count,
                actual.revision,
                actual.is_final
              ) = ROW(
                expected.open,
                expected.high,
                expected.low,
                expected.close,
                expected.volume,
                expected.trade_count,
                expected.revision,
                expected.is_final
              )
    ) AS matching_count,
    COUNT(actual.ticker) FILTER (
        WHERE actual.source <> expected.source
    ) AS protected_collision_count,
    COUNT(*) = COUNT(actual.ticker) FILTER (
        WHERE actual.source = expected.source
          AND ROW(
                actual.open,
                actual.high,
                actual.low,
                actual.close,
                actual.volume,
                actual.trade_count,
                actual.revision,
                actual.is_final
              ) = ROW(
                expected.open,
                expected.high,
                expected.low,
                expected.close,
                expected.volume,
                expected.trade_count,
                expected.revision,
                expected.is_final
              )
    ) AS seed_matches
FROM blog_seed_expected AS expected
LEFT JOIN minute_candles AS actual
    ON actual.ticker = expected.ticker
   AND actual.bucket_start = expected.bucket_start;

DO $$
DECLARE
    verification RECORD;
BEGIN
    SELECT * INTO verification FROM blog_seed_verification;
    IF NOT verification.seed_matches THEN
        RAISE EXCEPTION
            'Seed verification failed: matched %/% rows, protected % non-blog collision(s)',
            verification.matching_count,
            verification.expected_count,
            verification.protected_collision_count;
    END IF;
END
$$;

SELECT * FROM blog_seed_verification
\gset

COMMIT;
\echo seed_scope=:seed_scope
\echo seed_start_date=:seed_start_date
\echo source=blog-local-seed
\echo verified_rows=:matching_count/:expected_count
SQL
