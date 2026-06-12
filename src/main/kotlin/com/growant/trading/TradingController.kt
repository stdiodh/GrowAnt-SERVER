package com.growant.trading

import com.growant.common.web.ApiResponse
import com.growant.common.web.userId
import com.growant.trading.dto.OrderRequestDto
import com.growant.trading.dto.TradeDto
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class TradingController(private val service: TradingService) {

    @PostMapping("/api/orders")
    fun placeOrder(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody req: OrderRequestDto,
    ): ApiResponse<TradeDto> =
        ApiResponse.ok(service.placeOrder(jwt.userId, req.ticker, req.isBuy, req.qty))

    @GetMapping("/api/trades")
    fun trades(@AuthenticationPrincipal jwt: Jwt): ApiResponse<List<TradeDto>> =
        ApiResponse.ok(service.getTrades(jwt.userId))
}
