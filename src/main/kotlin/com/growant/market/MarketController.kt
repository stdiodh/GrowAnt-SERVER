package com.growant.market

import com.growant.common.web.ApiResponse
import com.growant.market.dto.MarketRowDto
import com.growant.market.dto.StockDetailDto
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/market")
class MarketController(private val service: MarketService) {

    @GetMapping
    fun list(): ApiResponse<List<MarketRowDto>> = ApiResponse.ok(service.getMarket())

    @GetMapping("/{ticker}")
    fun detail(@PathVariable ticker: String): ApiResponse<StockDetailDto> =
        ApiResponse.ok(service.getDetail(ticker))
}
