package com.growant.account

import com.growant.account.dto.AccountSummaryDto
import com.growant.portfolio.PortfolioOwner
import com.growant.portfolio.PortfolioService
import com.growant.trading.TradingService
import org.springframework.stereotype.Service
import kotlin.math.roundToLong

/**
 * 자산 요약 — 동적 산식: 총 평가 자산 = 현금(TradingService) + me 포트폴리오 평가액.
 * 수익률은 시드(1,000만) 대비. 초기값은 10,520,000 / +5.2%로 기존 불변값과 동일.
 */
@Service
class AccountService(
    private val tradingService: TradingService,
    private val portfolioService: PortfolioService,
) {
    private val seed = 10_000_000L

    fun getSummary(): AccountSummaryDto {
        val totalAsset = tradingService.getCash() +
            portfolioService.getPortfolio(PortfolioOwner.ME).value
        val returnRate = ((totalAsset - seed) * 1000.0 / seed).roundToLong() / 10.0
        return AccountSummaryDto(totalAsset = totalAsset, returnRate = returnRate)
    }
}
