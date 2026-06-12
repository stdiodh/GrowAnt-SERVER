package com.growant.account

import com.growant.account.dto.AccountSummaryDto
import com.growant.common.INITIAL_CASH
import com.growant.portfolio.PortfolioOwner
import com.growant.portfolio.PortfolioService
import com.growant.trading.TradingService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import kotlin.math.roundToLong

/**
 * 자산 요약 — 총 평가 자산 = 현금 + me 포트폴리오 평가, 수익률은 INITIAL_CASH 대비. 스펙 §6.3
 * REPEATABLE_READ 단일 스냅샷: 현금·포지션 읽기 사이에 커밋된 체결이 끼어도 합산이 어긋나지 않는다
 * (READ COMMITTED로는 두 읽기가 서로 다른 스냅샷을 볼 수 있다).
 */
@Service
class AccountService(
    private val tradingService: TradingService,
    private val portfolioService: PortfolioService,
) {

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun getSummary(userId: Long): AccountSummaryDto {
        val totalAsset = tradingService.getCash(userId) +
            portfolioService.getPortfolio(PortfolioOwner.ME, userId).value
        val returnRate = ((totalAsset - INITIAL_CASH) * 1000.0 / INITIAL_CASH).roundToLong() / 10.0
        return AccountSummaryDto(totalAsset = totalAsset, returnRate = returnRate)
    }
}
