package com.growant.market.candle

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CandleProperties::class)
class CandleConfiguration {
    @Bean
    fun minuteCandlePolicy(properties: CandleProperties): MinuteCandlePolicy = MinuteCandlePolicy(properties)

    @Bean
    fun marketClock(properties: CandleProperties): Clock = Clock.system(properties.zoneId)
}
