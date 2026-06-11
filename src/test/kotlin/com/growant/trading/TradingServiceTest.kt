package com.growant.trading

import com.growant.common.error.BusinessException
import com.growant.common.error.ErrorCode
import com.growant.market.MarketService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TradingServiceTest {
    private val market = MarketService()
    private val service = TradingService(market)

    private fun meValue(): Long {
        val prices = market.getMarket().associateBy { it.ticker }
        return service.getMePositions().sumOf { prices.getValue(it.ticker).price.toLong() * it.qty }
    }

    @Test
    fun `초기 상태 - 현금과 me평가 합이 자산 불변값`() {
        assertThat(service.getCash()).isEqualTo(7_638_200L)
        assertThat(meValue()).isEqualTo(2_881_800L)
        assertThat(service.getCash() + meValue()).isEqualTo(10_520_000L)
        assertThat(service.getTrades()).hasSize(6)
        assertThat(service.getTrades().first().name).isEqualTo("삼성전자")
        assertThat(service.getTrades().first().isBuy).isFalse()
    }

    @Test
    fun `매수 체결 - 현금 차감, 가중평단, 내역 prepend, 자산 불변`() {
        val before = service.getCash() + meValue()
        val t = service.placeOrder("005930", true, 1)
        assertThat(service.getCash()).isEqualTo(7_561_900L)
        val pos = service.getMePositions().first { it.ticker == "005930" }
        assertThat(pos.qty).isEqualTo(13)
        assertThat(pos.avgPrice).isEqualTo(70_485) // round(916,300/13)
        assertThat(t.name).isEqualTo("삼성전자")
        assertThat(t.isBuy).isTrue()
        assertThat(t.amount).isEqualTo(76_300L)
        assertThat(t.time).matches("""\d{2}\.\d{2} \d{2}:\d{2}""")
        assertThat(service.getTrades().first()).isEqualTo(t)
        assertThat(service.getTrades()).hasSize(7)
        assertThat(service.getCash() + meValue()).isEqualTo(before)
    }

    @Test
    fun `매도 체결 - 현금 증가, 수량 차감, 평단 유지`() {
        service.placeOrder("000270", false, 2) // 기아 109,500 × 2
        assertThat(service.getCash()).isEqualTo(7_857_200L)
        val pos = service.getMePositions().first { it.ticker == "000270" }
        assertThat(pos.qty).isEqualTo(4)
        assertThat(pos.avgPrice).isEqualTo(98_700)
    }

    @Test
    fun `전량 매도 시 포지션 제거`() {
        service.placeOrder("035420", false, 3) // NAVER 전량
        assertThat(service.getMePositions().none { it.ticker == "035420" }).isTrue()
    }

    @Test
    fun `신규 종목 매수 시 포지션 추가`() {
        service.placeOrder("005380", true, 1) // 현대차 247,000
        val pos = service.getMePositions().first { it.ticker == "005380" }
        assertThat(pos.qty).isEqualTo(1)
        assertThat(pos.avgPrice).isEqualTo(247_000)
        assertThat(service.getCash()).isEqualTo(7_391_200L)
    }

    @Test
    fun `검증 에러 4종`() {
        assertThatThrownBy { service.placeOrder("999999", true, 1) }
            .isInstanceOf(BusinessException::class.java)
            .satisfies({ assertThat((it as BusinessException).code).isEqualTo(ErrorCode.INVALID_TICKER) })
        assertThatThrownBy { service.placeOrder("005930", true, 0) }
            .satisfies({ assertThat((it as BusinessException).code).isEqualTo(ErrorCode.INVALID_ORDER) })
        assertThatThrownBy { service.placeOrder("005380", true, 31) } // 7,657,000 > 7,638,200
            .satisfies({ assertThat((it as BusinessException).code).isEqualTo(ErrorCode.ORDER_INSUFFICIENT_FUNDS) })
        assertThatThrownBy { service.placeOrder("000660", false, 5) } // 보유 4
            .satisfies({ assertThat((it as BusinessException).code).isEqualTo(ErrorCode.ORDER_INSUFFICIENT_HOLDINGS) })
        assertThatThrownBy { service.placeOrder("005380", false, 1) } // 미보유 종목 매도
            .satisfies({ assertThat((it as BusinessException).code).isEqualTo(ErrorCode.ORDER_INSUFFICIENT_HOLDINGS) })
    }
}
