package com.growant.portfolio

import com.growant.common.web.ApiResponse
import com.growant.portfolio.dto.PortfolioDto
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/portfolio")
class PortfolioController(private val service: PortfolioService) {

    @GetMapping("/me")
    fun me(): ApiResponse<PortfolioDto> = ApiResponse.ok(service.getPortfolio(PortfolioOwner.ME))

    @GetMapping("/ai")
    fun ai(): ApiResponse<PortfolioDto> = ApiResponse.ok(service.getPortfolio(PortfolioOwner.AI))
}
