package com.growant.auth

import com.growant.auth.dto.AuthResponseDto
import com.growant.auth.dto.UserDto
import com.growant.common.INITIAL_CASH
import com.growant.common.error.BusinessException
import com.growant.common.error.ErrorCode
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

/**
 * 데모 로그인 — (provider, nickname) find-or-create 후 HS256 JWT 발급. 스펙 §6.2
 * 가입 시 INITIAL_CASH 지급(빈 포트폴리오·빈 내역 시작). 실제 소셜 OAuth 전환 시 login 내부만 교체.
 *
 * login에 바깥 @Transactional을 두지 않는다: PostgreSQL은 제약 위반 시 트랜잭션을 중단시키므로
 * 같은 트랜잭션 안에서는 동시 가입 충돌 후 재조회가 불가하다 — repo 호출별 자체 트랜잭션으로 충분(쓰기 1회).
 */
@Service
class AuthService(
    private val userRepository: UserJpaRepository,
    private val jwtEncoder: JwtEncoder,
) {

    fun login(provider: String, nickname: String): AuthResponseDto {
        val name = nickname.trim()
        if (provider !in PROVIDERS || name.isEmpty() || name.length > 20) {
            throw BusinessException(ErrorCode.INVALID_LOGIN)
        }
        val user = findOrCreate(provider, name)
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

    private fun findOrCreate(provider: String, name: String): UserEntity =
        userRepository.findByProviderAndNickname(provider, name)
            ?: try {
                userRepository.saveAndFlush(UserEntity(provider = provider, nickname = name, cash = INITIAL_CASH))
            } catch (e: DataIntegrityViolationException) {
                // 동시 가입 레이스 — 유니크 제약이 한쪽만 통과시키므로 재조회로 멱등 처리(스펙 §6.2)
                userRepository.findByProviderAndNickname(provider, name) ?: throw e
            }

    companion object {
        private val PROVIDERS = setOf("kakao", "naver", "apple", "google")
        private val TOKEN_TTL: Duration = Duration.ofHours(24)
    }
}
