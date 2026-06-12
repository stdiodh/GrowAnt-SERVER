package com.growant.auth

import com.growant.auth.dto.AuthResponseDto
import com.growant.auth.dto.LoginRequestDto
import com.growant.auth.dto.UserDto
import com.growant.common.web.ApiResponse
import com.growant.common.web.userId
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(private val service: AuthService) {

    @PostMapping("/login")
    fun login(@RequestBody req: LoginRequestDto): ApiResponse<AuthResponseDto> =
        ApiResponse.ok(service.login(req.provider, req.nickname))

    /** 클레임 기반 — 스토어 조회 없음. 서버가 재시작돼도 유효 토큰이면 동작한다. 스펙 §3.2 */
    @GetMapping("/me")
    fun me(@AuthenticationPrincipal jwt: Jwt): ApiResponse<UserDto> = ApiResponse.ok(
        UserDto(
            jwt.userId,
            // 우리 AuthService가 발급한 토큰에만 서명이 통과하므로 두 클레임은 항상 존재 — 누락은 발급 코드 버그.
            jwt.getClaimAsString("nickname") ?: error("nickname claim missing"),
            jwt.getClaimAsString("provider") ?: error("provider claim missing"),
        ),
    )
}
