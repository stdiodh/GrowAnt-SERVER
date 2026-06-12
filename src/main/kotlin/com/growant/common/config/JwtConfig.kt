package com.growant.common.config

import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import javax.crypto.spec.SecretKeySpec

/** 데모 로그인용 자체 서명 HS256 JWT 인코더/디코더. 키는 JWT_SECRET(루트 .env) — 32자 미만이면 부팅 실패가 정상. 스펙 §3.3 */
@Configuration
class JwtConfig(@Value("\${auth.jwt.secret}") private val secret: String) {

    init {
        // HS256 최소 키 길이 — 여기서 막아야 'KDoc: 짧으면 부팅 실패'가 사실이 된다(없으면 첫 로그인 때 500).
        require(secret.toByteArray().size >= 32) {
            "auth.jwt.secret must be at least 256 bits (32 bytes), got ${secret.toByteArray().size} bytes"
        }
    }

    private val key = SecretKeySpec(secret.toByteArray(), "HmacSHA256")

    @Bean
    fun jwtEncoder(): JwtEncoder = NimbusJwtEncoder(ImmutableSecret<SecurityContext>(key))

    @Bean
    fun jwtDecoder(): JwtDecoder =
        NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build()
}
