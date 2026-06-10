package com.growant.account

import com.growant.account.dto.AccountSummaryDto
import org.springframework.stereotype.Service
import kotlin.math.roundToLong

/**
 * 결정적 자산 요약(시드 1,000만 · 현금 250만 · 주식 802만).
 * 계정 보유종목엔 카탈로그 외 종목(애플)이 있어 마켓 연동은 거래 슬라이스에서 대체한다.
 */
@Service
class AccountService {
    private val seed = 10_000_000L
    private val cash = 2_500_000L
    private val stockValue = 8_020_000L

    fun getSummary(): AccountSummaryDto {
        val totalAsset = cash + stockValue
        val returnRate = ((totalAsset - seed) * 1000.0 / seed).roundToLong() / 10.0
        return AccountSummaryDto(totalAsset = totalAsset, returnRate = returnRate)
    }
}
