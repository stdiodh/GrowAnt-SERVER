package com.growant.market

import com.growant.common.error.BusinessException
import com.growant.common.error.ErrorCode
import com.growant.market.dto.MarketRowDto
import com.growant.market.dto.StockDetailDto
import org.springframework.stereotype.Service
import kotlin.math.roundToInt
import kotlin.random.Random

@Service
class MarketService {
    private val catalog: Map<String, MarketRowDto> = listOf(
        MarketRowDto("005930", "삼성전자", 76300, 5.97),
        MarketRowDto("000660", "SK하이닉스", 178500, 3.41),
        MarketRowDto("035720", "카카오", 41200, -2.10),
        MarketRowDto("035420", "NAVER", 198400, 1.55),
        MarketRowDto("005380", "현대차", 247000, -0.81),
        MarketRowDto("000270", "기아", 109500, 0.37),
        MarketRowDto("068270", "셀트리온", 187000, -1.24),
        MarketRowDto("051910", "LG화학", 278000, 2.08),
    ).associateBy { it.ticker }

    fun getMarket(): List<MarketRowDto> = catalog.values.toList()

    fun getDetail(ticker: String): StockDetailDto {
        val row = catalog[ticker] ?: throw BusinessException(ErrorCode.INVALID_TICKER)
        return StockDetailDto(
            ticker = row.ticker,
            name = row.name,
            price = row.price,
            changeRate = row.changeRate,
            candles = candles(row.ticker, row.price),
            high52w = (row.price * 1.18).roundToInt(),
            low52w = (row.price * 0.72).roundToInt(),
            volume = 14_823_410L,
            marketCapEok = row.price.toLong() * 5_969_783_300L / 1_000_000L,
            per = 12.4,
            pbr = 1.2,
        )
    }

    private fun candles(ticker: String, price: Int): List<Int> {
        val rnd = Random(ticker.hashCode())
        val out = mutableListOf(price)
        var p = price
        repeat(9) {
            val delta = (p * rnd.nextDouble(-0.02, 0.02)).toInt()
            p = (p - delta).coerceAtLeast(1)
            out.add(p)
        }
        return out
    }
}
