package com.growant.market.candle.persistence

import com.growant.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.namedparam.SqlParameterSource
import java.nio.file.Files
import java.nio.file.Path
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

class MinuteCandleStorageMetricsIT(
    @Autowired val jdbc: NamedParameterJdbcTemplate,
) : PostgresIntegrationTest() {
    @Test
    fun `주간 5종목 분봉의 저장 크기와 배치 삽입 처리량을 기록한다`() {
        cleanupBenchmarkRows()
        val rows = benchmarkRows()
        val walStart = jdbc.jdbcOperations.queryForObject(
            "SELECT pg_current_wal_lsn()::text",
            String::class.java,
        )!!

        val startedAt = System.nanoTime()
        jdbc.batchUpdate(INSERT_SQL, rows)
        val elapsedNanos = System.nanoTime() - startedAt
        val walBytes = jdbc.jdbcOperations.queryForObject(
            "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), CAST(? AS pg_lsn))::bigint",
            Long::class.javaObjectType,
            walStart,
        )!!

        val storedRowCount = jdbc.queryForObject(
            COUNT_SQL,
            MapSqlParameterSource("tickers", TICKERS),
            Long::class.javaObjectType,
        )!!
        val metrics = jdbc.jdbcOperations.queryForObject(METRICS_SQL) { resultSet, _ ->
            StorageMetrics(
                heapBytes = resultSet.getLong("heap_bytes"),
                indexesBytes = resultSet.getLong("indexes_bytes"),
                totalBytes = resultSet.getLong("total_bytes"),
                walBytes = walBytes,
            )
        }!!

        assertThat(storedRowCount).isEqualTo(EXPECTED_ROW_COUNT.toLong())
        assertThat(metrics.heapBytes).isPositive()
        assertThat(metrics.indexesBytes).isPositive()
        assertThat(metrics.totalBytes).isPositive()
        assertThat(metrics.walBytes).isPositive()

        val postgresVersion = jdbc.jdbcOperations.queryForObject("SHOW server_version", String::class.java)!!
        val report = renderReport(
            metrics = metrics,
            rowCount = storedRowCount,
            elapsedNanos = elapsedNanos,
            postgresVersion = postgresVersion,
        )
        REPORT_PATH.parent?.let(Files::createDirectories)
        Files.writeString(REPORT_PATH, report)
        print(report)
    }

    private fun cleanupBenchmarkRows() {
        jdbc.update(
            "DELETE FROM minute_candles WHERE ticker IN (:tickers)",
            MapSqlParameterSource("tickers", TICKERS),
        )
        jdbc.jdbcOperations.execute("VACUUM (ANALYZE) minute_candles")
    }

    private fun benchmarkRows(): Array<SqlParameterSource> {
        val rows = ArrayList<SqlParameterSource>(EXPECTED_ROW_COUNT)

        repeat(TRADING_DAYS) { day ->
            repeat(MINUTES_PER_DAY) { minute ->
                TICKERS.forEachIndexed { tickerIndex, ticker ->
                    val open = 50_000 + tickerIndex * 10_000 + day * 100 + minute
                    rows += MapSqlParameterSource()
                        .addValue("ticker", ticker)
                        .addValue(
                            "bucketStart",
                            MARKET_OPEN.plusSeconds(day * SECONDS_PER_DAY + minute * 60L)
                                .atOffset(ZoneOffset.UTC),
                        )
                        .addValue("open", open)
                        .addValue("high", open + 50)
                        .addValue("low", open - 50)
                        .addValue("close", open + 10)
                        .addValue("volume", 1_000L + minute)
                        .addValue("tradeCount", 20L + minute % 10)
                        .addValue("revision", 0)
                        .addValue("isFinal", true)
                        .addValue("source", "storage-benchmark")
                }
            }
        }

        check(rows.size == EXPECTED_ROW_COUNT)
        return rows.toTypedArray()
    }

    private fun renderReport(
        metrics: StorageMetrics,
        rowCount: Long,
        elapsedNanos: Long,
        postgresVersion: String,
    ): String {
        val elapsedMillis = elapsedNanos / 1_000_000.0
        val rowsPerSecond = rowCount / (elapsedNanos / 1_000_000_000.0)
        val bytesPerRow = metrics.totalBytes.toDouble() / rowCount
        val walBytesPerRow = metrics.walBytes.toDouble() / rowCount

        return buildString {
            appendLine("# Minute Candle Storage Benchmark")
            appendLine()
            appendLine("- Generated at: ${Instant.now()}")
            appendLine("- PostgreSQL: $postgresVersion")
            appendLine("- Dataset: ${TICKERS.size} tickers × $MINUTES_PER_DAY minutes × $TRADING_DAYS days")
            appendLine("- Rows: ${integerFormat.format(rowCount)}")
            appendLine("- Insert method: one JDBC batch; row construction and cleanup excluded")
            appendLine()
            appendLine("| Metric | Result |")
            appendLine("| --- | ---: |")
            appendLine("| Table heap bytes | ${integerFormat.format(metrics.heapBytes)} |")
            appendLine("| Indexes bytes | ${integerFormat.format(metrics.indexesBytes)} |")
            appendLine("| Total relation bytes | ${integerFormat.format(metrics.totalBytes)} |")
            appendLine("| Total bytes per candle | ${decimalFormat.format(bytesPerRow)} |")
            appendLine("| WAL bytes for insert | ${integerFormat.format(metrics.walBytes)} |")
            appendLine("| WAL bytes per candle | ${decimalFormat.format(walBytesPerRow)} |")
            appendLine("| Batch insert duration | ${decimalFormat.format(elapsedMillis)} ms |")
            appendLine("| Batch insert throughput | ${integerFormat.format(rowsPerSecond)} rows/s |")
            appendLine()
            appendLine(
                "> Sizes use pg_table_size, pg_indexes_size, and pg_total_relation_size after inserting the dataset. " +
                    "They include PostgreSQL page overhead but exclude replicas and backups.",
            )
            appendLine(
                "> WAL uses pg_wal_lsn_diff around this single batch and can change with checkpoints, " +
                    "full-page writes, and PostgreSQL settings.",
            )
            appendLine("> Timing is an observation from this Testcontainers run, not a pass/fail threshold.")
        }
    }

    private data class StorageMetrics(
        val heapBytes: Long,
        val indexesBytes: Long,
        val totalBytes: Long,
        val walBytes: Long,
    )

    private companion object {
        val TICKERS = listOf("990001", "990002", "990003", "990004", "990005")
        val MARKET_OPEN: Instant = Instant.parse("2026-08-03T00:00:00Z")
        val REPORT_PATH: Path = Path.of(
            System.getProperty("user.dir"),
            "build/reports/market-data/minute-candle-storage-benchmark.md",
        )

        const val MINUTES_PER_DAY = 390
        const val TRADING_DAYS = 5
        const val SECONDS_PER_DAY = 86_400L
        const val EXPECTED_ROW_COUNT = 5 * MINUTES_PER_DAY * TRADING_DAYS

        const val INSERT_SQL = """
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
                source
            ) VALUES (
                :ticker,
                :bucketStart,
                :open,
                :high,
                :low,
                :close,
                :volume,
                :tradeCount,
                :revision,
                :isFinal,
                :source
            )
        """

        const val COUNT_SQL = """
            SELECT count(*)
            FROM minute_candles
            WHERE ticker IN (:tickers)
        """

        const val METRICS_SQL = """
            SELECT
                pg_table_size('minute_candles'::regclass)::bigint AS heap_bytes,
                pg_indexes_size('minute_candles'::regclass)::bigint AS indexes_bytes,
                pg_total_relation_size('minute_candles'::regclass)::bigint AS total_bytes
        """

        val decimalFormat = DecimalFormat("#,##0.000", DecimalFormatSymbols.getInstance(Locale.ROOT))
        val integerFormat = DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.ROOT))
    }
}
