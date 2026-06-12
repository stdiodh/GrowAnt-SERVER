package com.growant.account

import com.growant.account.dto.AccountSummaryDto
import com.growant.common.web.ApiResponse
import com.growant.common.web.userId
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/account")
class AccountController(private val service: AccountService) {

    @GetMapping("/summary")
    fun summary(@AuthenticationPrincipal jwt: Jwt): ApiResponse<AccountSummaryDto> =
        ApiResponse.ok(service.getSummary(jwt.userId))
}
