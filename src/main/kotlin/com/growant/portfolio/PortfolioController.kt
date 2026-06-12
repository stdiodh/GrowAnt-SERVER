package com.growant.portfolio

import com.growant.common.web.ApiResponse
import com.growant.common.web.userId
import com.growant.portfolio.dto.PortfolioDto
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/portfolio")
class PortfolioController(private val service: PortfolioService) {

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal jwt: Jwt): ApiResponse<PortfolioDto> =
        ApiResponse.ok(service.getPortfolio(PortfolioOwner.ME, jwt.userId))

    @GetMapping("/ai")
    fun ai(@AuthenticationPrincipal jwt: Jwt): ApiResponse<PortfolioDto> =
        ApiResponse.ok(service.getPortfolio(PortfolioOwner.AI, jwt.userId))
}
