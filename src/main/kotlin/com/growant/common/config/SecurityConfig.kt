package com.growant.common.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class SecurityConfig(private val authEntryPoint: ApiAuthEntryPoint) {
    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers("/api/auth/login").permitAll() // me는 보호(JWT 클레임 필요)
                it.anyRequest().authenticated()
            }
            .oauth2ResourceServer {
                it.jwt { } // JwtConfig의 JwtDecoder 빈 사용
                it.authenticationEntryPoint(authEntryPoint)
            }
        return http.build()
    }
}
