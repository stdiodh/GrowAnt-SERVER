package com.growant.market.candle.persistence

import com.growant.market.candle.MinuteCandle
import com.growant.market.candle.port.MinuteCandleSaveResult
import com.growant.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Instant
import java.time.ZoneOffset

class MinuteCandleStoreIT(
    @Autowired val store: MinuteCandleStore,
    @Autowired val jdbc: NamedParameterJdbcTemplate,
) : PostgresIntegrationTest() {
    @Test
    fun `분봉을 저장하고 같은 revision 재처리는 중복 행을 만들지 않는다`() {
        val candle = candle(ticker = "005931", bucketStart = BASE_TIME)

        assertThat(store.upsert(candle)).isEqualTo(1)
        assertThat(store.upsert(candle)).isZero()

        assertThat(store.find("005931", BASE_TIME, BASE_TIME.plusSeconds(60)))
            .containsExactly(candle)
    }

    @Test
    fun `저장 결과는 동일 재처리와 revision 충돌을 구분한다`() {
        val original = candle(ticker = "005937", bucketStart = BASE_TIME, revision = 1)

        assertThat(store.save(original)).isEqualTo(MinuteCandleSaveResult.INSERTED_OR_UPDATED)
        assertThat(store.save(original)).isEqualTo(MinuteCandleSaveResult.UNCHANGED)
        assertThat(store.save(original.copy(close = 109, source = "conflicting-feed")))
            .isEqualTo(MinuteCandleSaveResult.REVISION_CONFLICT)
        assertThat(store.save(original.copy(revision = 0, source = "stale-feed")))
            .isEqualTo(MinuteCandleSaveResult.STALE_REVISION)

        assertThat(store.find("005937", BASE_TIME, BASE_TIME.plusSeconds(60)))
            .containsExactly(original)
    }

    @Test
    fun `높은 revision은 기존 분봉을 보정한다`() {
        val original = candle(ticker = "005932", bucketStart = BASE_TIME, revision = 0)
        val corrected = original.copy(
            high = 115,
            close = 110,
            volume = 1_500,
            tradeCount = 15,
            revision = 1,
            isFinal = true,
            source = "rest-reconcile",
        )

        assertThat(store.upsert(original)).isEqualTo(1)
        assertThat(store.upsert(corrected)).isEqualTo(1)

        assertThat(store.find("005932", BASE_TIME, BASE_TIME.plusSeconds(60)))
            .containsExactly(corrected)
    }

    @Test
    fun `낮은 revision은 최신 분봉을 덮어쓰지 않는다`() {
        val latest = candle(
            ticker = "005933",
            bucketStart = BASE_TIME,
            close = 110,
            revision = 2,
            isFinal = true,
        )
        val stale = latest.copy(close = 90, revision = 1, isFinal = false, source = "late-event")

        assertThat(store.upsert(latest)).isEqualTo(1)
        assertThat(store.upsert(stale)).isZero()

        assertThat(store.find("005933", BASE_TIME, BASE_TIME.plusSeconds(60)))
            .containsExactly(latest)
    }

    @Test
    fun `기간 조회는 시작 포함 종료 제외로 시간 오름차순 반환한다`() {
        val before = candle(ticker = "005934", bucketStart = BASE_TIME.minusSeconds(60))
        val first = candle(ticker = "005934", bucketStart = BASE_TIME)
        val second = candle(ticker = "005934", bucketStart = BASE_TIME.plusSeconds(60))
        val atExclusiveEnd = candle(ticker = "005934", bucketStart = BASE_TIME.plusSeconds(120))
        val otherTicker = candle(ticker = "005935", bucketStart = BASE_TIME.plusSeconds(60))

        listOf(second, otherTicker, atExclusiveEnd, before, first).forEach { store.upsert(it) }

        assertThat(store.find("005934", BASE_TIME, BASE_TIME.plusSeconds(120)))
            .containsExactly(first, second)
    }

    @Test
    fun `OHLC 관계가 잘못된 행은 DB check 제약으로 거부된다`() {
        val parameters = MapSqlParameterSource()
            .addValue("ticker", "005936")
            .addValue("bucketStart", BASE_TIME.atOffset(ZoneOffset.UTC))
            .addValue("open", 100)
            .addValue("high", 90)
            .addValue("low", 80)
            .addValue("close", 85)
            .addValue("volume", 1_000L)
            .addValue("tradeCount", 10L)
            .addValue("revision", 0)
            .addValue("isFinal", false)
            .addValue("source", "invalid-test")

        assertThatThrownBy { jdbc.update(INSERT_SQL, parameters) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    private fun candle(
        ticker: String,
        bucketStart: Instant,
        open: Int = 100,
        high: Int = 110,
        low: Int = 90,
        close: Int = 105,
        volume: Long = 1_000,
        tradeCount: Long = 10,
        revision: Int = 0,
        isFinal: Boolean = false,
        source: String = "websocket",
    ) = MinuteCandle(
        ticker = ticker,
        bucketStart = bucketStart,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume,
        tradeCount = tradeCount,
        revision = revision,
        isFinal = isFinal,
        source = source,
    )

    private companion object {
        val BASE_TIME: Instant = Instant.parse("2026-08-07T00:00:00Z")

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
    }
}
