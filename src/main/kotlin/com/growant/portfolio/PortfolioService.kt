package com.growant.portfolio

import com.growant.market.MarketService
import com.growant.portfolio.dto.HoldingDto
import com.growant.portfolio.dto.PortfolioDto
import com.growant.trading.Position
import com.growant.trading.TradingService
import org.springframework.stereotype.Service
import kotlin.math.roundToLong

/**
 * 대결 포트폴리오 조회 — ME 포지션은 TradingService 상태(주문 반영), 현재가·종목명은 MarketService.
 * 합산(cost/value/profit/returnRate)은 서버 권위로 계산해 내려준다.
 */
@Service
class PortfolioService(
    private val marketService: MarketService,
    private val tradingService: TradingService,
) {

    // NOTE(duel-ai): AI 포지션은 임시 하드코딩 — AI 매매 로직 슬라이스에서
    //   TradingService 상태로 대체하고 이 블록을 삭제한다.
    private val aiPositions = listOf(
        Position("005930", 8, 73_500),
        Position("051910", 3, 272_000),
        Position("068270", 5, 192_000),
        Position("035720", 20, 36_110),
    )

    fun getPortfolio(owner: PortfolioOwner, userId: Long): PortfolioDto {
        val positions = when (owner) {
            PortfolioOwner.ME -> tradingService.getMePositions(userId)
            PortfolioOwner.AI -> aiPositions // NOTE(duel-ai): userId 무관 — AI 매매 슬라이스에서 대체
        }
        val market = marketService.getMarket().associateBy { it.ticker }
        val holdings = positions.map { p ->
            // 포지션은 카탈로그 기반 — 불일치는 사용자 입력이 아닌 프로그래머 오류라 의도적으로 500(IllegalStateException).
            val row = checkNotNull(market[p.ticker]) { "ticker ${p.ticker} not in market catalog" }
            HoldingDto(p.ticker, row.name, p.qty, p.avgPrice, row.price)
        }
        val cost = holdings.sumOf { it.avgPrice.toLong() * it.qty }
        val value = holdings.sumOf { it.currentPrice.toLong() * it.qty }
        val profit = value - cost
        val returnRate = if (cost == 0L) 0.0 else (profit * 1000.0 / cost).roundToLong() / 10.0
        return PortfolioDto(returnRate, profit, cost, value, holdings)
    }
}
