package com.growant.trading

import com.growant.common.web.ApiResponse
import com.growant.trading.dto.OrderRequestDto
import com.growant.trading.dto.TradeDto
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class TradingController(private val service: TradingService) {

    @PostMapping("/api/orders")
    fun placeOrder(@RequestBody req: OrderRequestDto): ApiResponse<TradeDto> =
        ApiResponse.ok(service.placeOrder(req.ticker, req.isBuy, req.qty))

    @GetMapping("/api/trades")
    fun trades(): ApiResponse<List<TradeDto>> = ApiResponse.ok(service.getTrades())
}
