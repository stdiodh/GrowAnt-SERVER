package com.growant.trading

import com.growant.auth.UserJpaRepository
import com.growant.common.error.BusinessException
import com.growant.common.error.ErrorCode
import com.growant.market.MarketService
import com.growant.trading.dto.TradeDto
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.math.roundToInt

/** 보유 포지션(수량·평균단가). 현재가·종목명은 마켓 카탈로그가 원천. */
data class Position(val ticker: String, val qty: Int, val avgPrice: Int)

/**
 * 거래 상태 소유자 — 현금·포지션·내역을 PostgreSQL에 영속(per-user). 스펙 §6.1
 * 동시성: placeOrder는 사용자 행 비관적 잠금(findForUpdate)으로 직렬화 — @Synchronized 대체.
 */
@Service
class TradingService(
    private val marketService: MarketService,
    private val userRepository: UserJpaRepository,
    private val positionRepository: PositionJpaRepository,
    private val tradeRepository: TradeJpaRepository,
) {

    @Transactional(readOnly = true)
    fun getCash(userId: Long): Long = requireUser(userId).cash

    @Transactional(readOnly = true)
    fun getMePositions(userId: Long): List<Position> =
        positionRepository.findByUserId(userId).map { it.toDomain() }

    @Transactional(readOnly = true)
    fun getTrades(userId: Long): List<TradeDto> =
        tradeRepository.findByUserIdOrderByExecutedAtDescIdDesc(userId).map { it.toDto() }

    @Transactional
    fun placeOrder(userId: Long, ticker: String, isBuy: Boolean, qty: Int): TradeDto {
        if (qty < 1) throw BusinessException(ErrorCode.INVALID_ORDER)
        val row = marketService.getMarket().associateBy { it.ticker }[ticker]
            ?: throw BusinessException(ErrorCode.INVALID_TICKER)
        // DB 리셋 후 옛 토큰의 sub가 미존재할 수 있다 — 401로 재로그인 유도(스펙 §6.1)
        val user = userRepository.findForUpdate(userId)
            ?: throw BusinessException(ErrorCode.UNAUTHENTICATED)
        val amount = row.price.toLong() * qty

        if (isBuy) {
            if (amount > user.cash) throw BusinessException(ErrorCode.ORDER_INSUFFICIENT_FUNDS)
            user.cash -= amount
            val held = positionRepository.findByUserIdAndTicker(userId, ticker)
            if (held != null) {
                val newQty = held.qty + qty
                held.avgPrice =
                    ((held.avgPrice.toLong() * held.qty + row.price.toLong() * qty).toDouble() / newQty)
                        .roundToInt()
                held.qty = newQty
            } else {
                positionRepository.save(
                    PositionEntity(userId = userId, ticker = ticker, qty = qty, avgPrice = row.price),
                )
            }
        } else {
            val held = positionRepository.findByUserIdAndTicker(userId, ticker)
            if (held == null || qty > held.qty) {
                throw BusinessException(ErrorCode.ORDER_INSUFFICIENT_HOLDINGS)
            }
            user.cash += amount
            if (held.qty == qty) positionRepository.delete(held) else held.qty -= qty
        }

        return tradeRepository.save(
            TradeEntity(
                userId = userId, ticker = ticker, name = row.name, isBuy = isBuy,
                price = row.price, qty = qty, amount = amount,
            ),
        ).toDto()
    }

    // 읽기 경로는 잠금 불필요 — 쓰기 직렬화는 placeOrder의 findForUpdate 전용(의도된 비대칭).
    private fun requireUser(userId: Long): com.growant.auth.UserEntity =
        userRepository.findById(userId).orElseThrow { BusinessException(ErrorCode.UNAUTHENTICATED) }
}
