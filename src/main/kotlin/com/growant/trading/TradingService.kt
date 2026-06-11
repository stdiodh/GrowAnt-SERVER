package com.growant.trading

import com.growant.common.error.BusinessException
import com.growant.common.error.ErrorCode
import com.growant.market.MarketService
import com.growant.trading.dto.TradeDto
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** 보유 포지션(수량·평균단가). 현재가·종목명은 마켓 카탈로그가 원천. */
data class Position(val ticker: String, val qty: Int, val avgPrice: Int)

/**
 * 거래 상태의 유일한 소유자 — 현금·me 포지션·거래내역(in-memory, 재시작 시 초기화).
 * 자산 불변식: 체결 직후 (현금 + me 평가액)은 체결 전과 같다(현금 증감 = 평가 증감).
 * 초기 현금 7,638,200 = 자산 10,520,000 − me 평가 2,881,800.
 * 영속성 슬라이스에서 내부 저장만 JPA로 교체한다(공개 메서드 시그니처 유지).
 */
@Service
class TradingService(private val marketService: MarketService) {

    private var cash: Long = 7_638_200L

    private val mePositions = mutableListOf(
        Position("005930", 12, 70_000),
        Position("000660", 4, 185_000),
        Position("035420", 3, 189_000),
        Position("000270", 6, 98_700),
    )

    // 기존 mock 내역 시드 — 내역 탭 첫 화면 동일(최신이 [0])
    private val trades = mutableListOf(
        TradeDto("삼성전자", false, 76_300, 10, 763_000L, "05.10 14:32"),
        TradeDto("애플", true, 252_000, 2, 504_000L, "05.10 10:08"),
        TradeDto("SK하이닉스", true, 180_700, 5, 903_500L, "05.09 15:18"),
        TradeDto("카카오", false, 41_200, 10, 412_000L, "05.09 11:45"),
        TradeDto("삼성전자", true, 72_000, 10, 720_000L, "05.08 09:35"),
        TradeDto("NAVER", false, 198_400, 1, 198_400L, "05.07 16:01"),
    )

    // 읽기도 모니터 안에서 — placeOrder와의 동시 접근 시 찢긴 읽기/CME 방지
    fun getCash(): Long = synchronized(this) { cash }
    fun getMePositions(): List<Position> = synchronized(this) { mePositions.toList() }
    fun getTrades(): List<TradeDto> = synchronized(this) { trades.toList() }

    @Synchronized
    fun placeOrder(ticker: String, isBuy: Boolean, qty: Int): TradeDto {
        if (qty < 1) throw BusinessException(ErrorCode.INVALID_ORDER)
        val row = marketService.getMarket().associateBy { it.ticker }[ticker]
            ?: throw BusinessException(ErrorCode.INVALID_TICKER)
        val amount = row.price.toLong() * qty

        if (isBuy) {
            if (amount > cash) throw BusinessException(ErrorCode.ORDER_INSUFFICIENT_FUNDS)
            cash -= amount
            val idx = mePositions.indexOfFirst { it.ticker == ticker }
            if (idx >= 0) {
                val p = mePositions[idx]
                val newQty = p.qty + qty
                val newAvg =
                    ((p.avgPrice.toLong() * p.qty + row.price.toLong() * qty).toDouble() / newQty).roundToInt()
                mePositions[idx] = p.copy(qty = newQty, avgPrice = newAvg)
            } else {
                mePositions.add(Position(ticker, qty, row.price))
            }
        } else {
            val idx = mePositions.indexOfFirst { it.ticker == ticker }
            val held = if (idx >= 0) mePositions[idx].qty else 0
            if (qty > held) throw BusinessException(ErrorCode.ORDER_INSUFFICIENT_HOLDINGS)
            cash += amount
            val p = mePositions[idx]
            if (p.qty == qty) mePositions.removeAt(idx) else mePositions[idx] = p.copy(qty = p.qty - qty)
        }

        val trade = TradeDto(
            name = row.name, isBuy = isBuy, price = row.price, qty = qty, amount = amount,
            time = LocalDateTime.now(ZoneId.of("Asia/Seoul")).format(TIME_FMT),
        )
        trades.add(0, trade)
        return trade
    }

    companion object {
        private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM.dd HH:mm")
    }
}
