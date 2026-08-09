package com.growant.market.candle

import com.growant.common.error.BusinessException
import com.growant.common.error.ErrorCode
import com.growant.common.web.ApiResponse
import com.growant.market.candle.dto.MinuteCandleSeriesDto
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

@RestController
@RequestMapping("/api/market/{ticker}/candles")
class MinuteCandleController(
    private val service: MinuteCandleQueryService,
) {
    @GetMapping
    fun getCandles(
        @PathVariable ticker: String,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
    ): ApiResponse<MinuteCandleSeriesDto> {
        val fromInclusive = parseRangeBoundary(from)
        val toExclusive = parseRangeBoundary(to)
        return ApiResponse.ok(service.getCandles(ticker, fromInclusive.toInstant(), toExclusive.toInstant()))
    }

    private fun parseRangeBoundary(value: String?): OffsetDateTime = try {
        OffsetDateTime.parse(value ?: throw BusinessException(ErrorCode.INVALID_CANDLE_RANGE))
    } catch (exception: DateTimeParseException) {
        throw BusinessException(ErrorCode.INVALID_CANDLE_RANGE)
    }
}
