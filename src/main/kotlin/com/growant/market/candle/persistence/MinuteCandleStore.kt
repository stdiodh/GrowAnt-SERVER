package com.growant.market.candle.persistence

import com.growant.market.candle.MinuteCandle
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class MinuteCandleStore(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    @Transactional
    fun upsert(candle: MinuteCandle): Int = jdbc.update(UPSERT_SQL, candle.parameters())

    @Transactional(readOnly = true)
    fun find(
        ticker: String,
        fromInclusive: Instant,
        toExclusive: Instant,
    ): List<MinuteCandle> = jdbc.query(
        FIND_SQL,
        MapSqlParameterSource()
            .addValue("ticker", ticker)
            .addValue("fromInclusive", fromInclusive.atUtc())
            .addValue("toExclusive", toExclusive.atUtc()),
        { resultSet, _ -> resultSet.toMinuteCandle() },
    )

    private fun MinuteCandle.parameters() = MapSqlParameterSource()
        .addValue("ticker", ticker)
        .addValue("bucketStart", bucketStart.atUtc())
        .addValue("open", open)
        .addValue("high", high)
        .addValue("low", low)
        .addValue("close", close)
        .addValue("volume", volume)
        .addValue("tradeCount", tradeCount)
        .addValue("revision", revision)
        .addValue("isFinal", isFinal)
        .addValue("source", source)

    private fun ResultSet.toMinuteCandle() = MinuteCandle(
        ticker = getString("ticker"),
        bucketStart = getObject("bucket_start", OffsetDateTime::class.java).toInstant(),
        open = getInt("open"),
        high = getInt("high"),
        low = getInt("low"),
        close = getInt("close"),
        volume = getLong("volume"),
        tradeCount = getLong("trade_count"),
        revision = getInt("revision"),
        isFinal = getBoolean("is_final"),
        source = getString("source"),
    )

    private fun Instant.atUtc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    private companion object {
        const val UPSERT_SQL = """
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
                :source,
                now(),
                now()
            )
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
                updated_at = now()
            WHERE minute_candles.revision < EXCLUDED.revision
        """

        const val FIND_SQL = """
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
                source
            FROM minute_candles
            WHERE ticker = :ticker
              AND bucket_start >= :fromInclusive
              AND bucket_start < :toExclusive
            ORDER BY bucket_start ASC
        """
    }
}
