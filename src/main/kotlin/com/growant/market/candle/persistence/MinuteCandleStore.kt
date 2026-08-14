package com.growant.market.candle.persistence

import com.growant.market.candle.MinuteCandle
import com.growant.market.candle.port.MinuteCandleRepository
import com.growant.market.candle.port.MinuteCandleSaveResult
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
) : MinuteCandleRepository {
    @Transactional
    override fun save(candle: MinuteCandle): MinuteCandleSaveResult {
        val parameters = candle.parameters()
        if (jdbc.update(SAVE_SQL, parameters) == 1) {
            return MinuteCandleSaveResult.INSERTED_OR_UPDATED
        }

        val stored = jdbc.query(EXISTING_SQL, parameters) { resultSet, _ ->
            resultSet.toMinuteCandle()
        }.singleOrNull()

        return when {
            stored == candle -> MinuteCandleSaveResult.UNCHANGED
            stored != null && stored.revision > candle.revision -> MinuteCandleSaveResult.STALE_REVISION
            else -> MinuteCandleSaveResult.REVISION_CONFLICT
        }
    }

    @Transactional
    override fun upsert(candle: MinuteCandle): Int = when (save(candle)) {
        MinuteCandleSaveResult.INSERTED_OR_UPDATED -> 1
        MinuteCandleSaveResult.UNCHANGED,
        MinuteCandleSaveResult.STALE_REVISION,
        MinuteCandleSaveResult.REVISION_CONFLICT,
        -> 0
    }

    @Transactional(readOnly = true)
    override fun find(
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
        const val SAVE_SQL = """
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

        const val EXISTING_SQL = """
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
              AND bucket_start = :bucketStart
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
