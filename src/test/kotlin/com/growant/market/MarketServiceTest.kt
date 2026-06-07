package com.growant.market

import com.growant.common.error.BusinessException
import com.growant.common.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MarketServiceTest {
    private val service = MarketService()

    @Test
    fun `getMarket returns 8 catalog rows starting with Samsung`() {
        val rows = service.getMarket()
        assertThat(rows).hasSize(8)
        assertThat(rows.first().ticker).isEqualTo("005930")
        assertThat(rows.first().name).isEqualTo("삼성전자")
        assertThat(rows.first().price).isEqualTo(76300)
    }

    @Test
    fun `getDetail returns deterministic 10-point candles ending(recent) at price`() {
        val a = service.getDetail("005930")
        val b = service.getDetail("005930")
        assertThat(a.candles).hasSize(10)
        assertThat(a.candles.first()).isEqualTo(76300) // [0]=최근=현재가
        assertThat(a.candles).isEqualTo(b.candles)     // 결정적(동일)
        assertThat(a.high52w).isEqualTo(90034)         // 76300*1.18 반올림
        assertThat(a.low52w).isEqualTo(54936)          // 76300*0.72 반올림
    }

    @Test
    fun `getDetail throws INVALID_TICKER for unknown ticker`() {
        assertThatThrownBy { service.getDetail("999999") }
            .isInstanceOf(BusinessException::class.java)
            .satisfies({ assertThat((it as BusinessException).code).isEqualTo(ErrorCode.INVALID_TICKER) })
    }
}
