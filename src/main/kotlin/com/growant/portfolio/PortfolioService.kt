package com.growant.portfolio

import com.growant.market.MarketService
import com.growant.portfolio.dto.HoldingDto
import com.growant.portfolio.dto.PortfolioDto
import org.springframework.stereotype.Service
import kotlin.math.roundToLong

/**
 * 결정적 대결 포트폴리오 — 수량·평균단가만 보유한다.
 * 현재가·종목명은 MarketService 카탈로그가 단일 원천(가격 불일치 원천 차단).
 * 합산(cost/value/profit/returnRate)은 서버 권위로 계산해 내려준다.
 */
@Service
class PortfolioService(private val marketService: MarketService) {

    private data class Position(val ticker: String, val qty: Int, val avgPrice: Int)

    private val positions: Map<PortfolioOwner, List<Position>> = mapOf(
        PortfolioOwner.ME to listOf(
            Position("005930", 12, 70_000),
            Position("000660", 4, 185_000),
            Position("035420", 3, 189_000),
            Position("000270", 6, 98_700),
        ),
        PortfolioOwner.AI to listOf(
            Position("005930", 8, 73_500),
            Position("051910", 3, 272_000),
            Position("068270", 5, 192_000),
            Position("035720", 20, 36_110),
        ),
    )

    fun getPortfolio(owner: PortfolioOwner): PortfolioDto {
        val market = marketService.getMarket().associateBy { it.ticker }
        val holdings = positions.getValue(owner).map { p ->
            // positions는 하드코딩 카탈로그 — 불일치는 사용자 입력이 아닌 프로그래머 오류라 의도적으로 500(IllegalStateException).
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
