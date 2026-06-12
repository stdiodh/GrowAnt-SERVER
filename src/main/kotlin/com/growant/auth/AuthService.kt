package com.growant.auth

import com.growant.auth.dto.AuthResponseDto
import com.growant.auth.dto.UserDto
import com.growant.common.error.BusinessException
import com.growant.common.error.ErrorCode
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

/**
 * 데모 로그인 — 비밀번호 없이 (provider, nickname) find-or-create 후 HS256 JWT 발급.
 * 실제 소셜 OAuth 전환 시 이 login 내부(인가코드 검증)만 교체한다. 스펙 §3.2
 */
@Service
class AuthService(private val userStore: UserStore, private val jwtEncoder: JwtEncoder) {

    fun login(provider: String, nickname: String): AuthResponseDto {
        val name = nickname.trim()
        if (provider !in PROVIDERS || name.isEmpty() || name.length > 20) {
            throw BusinessException(ErrorCode.INVALID_LOGIN)
        }
        val user = userStore.findOrCreate(provider, name)
        val now = Instant.now()
        val claims = JwtClaimsSet.builder()
            .issuer("growant")
            .subject(user.id.toString())
            .claim("nickname", user.nickname)
            .claim("provider", user.provider)
            .issuedAt(now)
            .expiresAt(now.plus(TOKEN_TTL))
            .build()
        val token = jwtEncoder
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .tokenValue
        return AuthResponseDto(token, UserDto(user.id, user.nickname, user.provider))
    }

    companion object {
        private val PROVIDERS = setOf("kakao", "naver", "apple", "google")
        private val TOKEN_TTL: Duration = Duration.ofHours(24)
    }
}
