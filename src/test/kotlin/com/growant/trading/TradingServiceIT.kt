package com.growant.trading

import com.growant.auth.UserEntity
import com.growant.auth.UserJpaRepository
import com.growant.common.INITIAL_CASH
import com.growant.common.error.BusinessException
import com.growant.common.error.ErrorCode
import com.growant.market.MarketService
import com.growant.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class TradingServiceIT(
    @Autowired val service: TradingService,
    @Autowired val users: UserJpaRepository,
    @Autowired val positions: PositionJpaRepository,
    @Autowired val market: MarketService,
) : PostgresIntegrationTest() {

    private fun newUser(nick: String): Long =
        users.save(UserEntity(provider = "kakao", nickname = nick, cash = INITIAL_CASH)).id

    private fun assets(userId: Long): Long {
        val prices = market.getMarket().associateBy { it.ticker }
        return service.getCash(userId) +
            service.getMePositions(userId).sumOf { prices.getValue(it.ticker).price.toLong() * it.qty }
    }

    @Test
    fun `신규 매수 - 현금 차감·포지션 생성·내역 기록`() {
        val u = newUser("잇-매수")
        val t = service.placeOrder(u, "005930", true, 1) // 삼성전자 76,300
        assertThat(service.getCash(u)).isEqualTo(INITIAL_CASH - 76_300L)
        val pos = service.getMePositions(u).single()
        assertThat(pos.ticker).isEqualTo("005930")
        assertThat(pos.qty).isEqualTo(1)
        assertThat(pos.avgPrice).isEqualTo(76_300)
        assertThat(t.name).isEqualTo("삼성전자")
        assertThat(t.amount).isEqualTo(76_300L)
        assertThat(t.time).matches("""\d{2}\.\d{2} \d{2}:\d{2}""")
        assertThat(service.getTrades(u)).hasSize(1)
    }

    @Test
    fun `추가 매수 - 가중평단 재계산`() {
        val u = newUser("잇-평단")
        positions.save(PositionEntity(userId = u, ticker = "005930", qty = 12, avgPrice = 70_000))
        service.placeOrder(u, "005930", true, 1) // 76,300
        val pos = service.getMePositions(u).single()
        assertThat(pos.qty).isEqualTo(13)
        assertThat(pos.avgPrice).isEqualTo(70_485) // round(916,300/13)
    }

    @Test
    fun `매도 - 현금 증가·수량 차감·평단 유지`() {
        val u = newUser("잇-매도")
        positions.save(PositionEntity(userId = u, ticker = "000270", qty = 6, avgPrice = 98_700))
        service.placeOrder(u, "000270", false, 2) // 기아 109,500 × 2
        assertThat(service.getCash(u)).isEqualTo(INITIAL_CASH + 219_000L)
        val pos = service.getMePositions(u).single()
        assertThat(pos.qty).isEqualTo(4)
        assertThat(pos.avgPrice).isEqualTo(98_700)
    }

    @Test
    fun `전량 매도 - 포지션 삭제`() {
        val u = newUser("잇-전량")
        positions.save(PositionEntity(userId = u, ticker = "035420", qty = 3, avgPrice = 189_000))
        service.placeOrder(u, "035420", false, 3)
        assertThat(service.getMePositions(u)).isEmpty()
    }

    @Test
    fun `검증 에러 - 수량·티커·잔고·보유·미보유`() {
        val u = newUser("잇-에러")
        positions.save(PositionEntity(userId = u, ticker = "000660", qty = 4, avgPrice = 178_500))
        listOf(
            { service.placeOrder(u, "005930", true, 0) } to ErrorCode.INVALID_ORDER,
            { service.placeOrder(u, "999999", true, 1) } to ErrorCode.INVALID_TICKER,
            { service.placeOrder(u, "005380", true, 41) } to ErrorCode.ORDER_INSUFFICIENT_FUNDS, // 247,000×41 = 10,127,000 > 10,000,000
            { service.placeOrder(u, "000660", false, 5) } to ErrorCode.ORDER_INSUFFICIENT_HOLDINGS, // 보유 4
            { service.placeOrder(u, "005380", false, 1) } to ErrorCode.ORDER_INSUFFICIENT_HOLDINGS, // 미보유
        ).forEach { (call, code) ->
            assertThatThrownBy { call() }
                .isInstanceOf(BusinessException::class.java)
                .satisfies({ assertThat((it as BusinessException).code).isEqualTo(code) })
        }
    }

    @Test
    fun `체결 직후 자산 불변 - 현금 증감 = 평가 증감`() {
        val u = newUser("잇-불변")
        val before = assets(u)
        service.placeOrder(u, "005930", true, 2)
        assertThat(assets(u)).isEqualTo(before)
        service.placeOrder(u, "005930", false, 1)
        assertThat(assets(u)).isEqualTo(before)
    }

    @Test
    fun `내역은 최신순`() {
        val u = newUser("잇-내역")
        service.placeOrder(u, "005930", true, 1)
        service.placeOrder(u, "000660", true, 1)
        val trades = service.getTrades(u)
        assertThat(trades).hasSize(2)
        assertThat(trades.first().name).isEqualTo("SK하이닉스")
    }

    @Test
    fun `미존재 사용자 주문은 UNAUTHENTICATED - DB 리셋 후 옛 토큰 시나리오`() {
        assertThatThrownBy { service.placeOrder(999_999L, "005930", true, 1) }
            .isInstanceOf(BusinessException::class.java)
            .satisfies({ assertThat((it as BusinessException).code).isEqualTo(ErrorCode.UNAUTHENTICATED) })
    }
}
