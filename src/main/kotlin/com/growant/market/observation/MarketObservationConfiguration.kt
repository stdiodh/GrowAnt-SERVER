package com.growant.market.observation

import com.growant.market.observation.application.ObservationCleanupService
import com.growant.market.observation.export.ObservationEvidenceExportService
import com.growant.market.observation.export.ObservationEvidenceManifestWriter
import com.growant.market.observation.policy.ClockHealthPolicy
import com.growant.market.observation.policy.ObservationActivationPolicy
import com.growant.market.observation.policy.ReportedEventAgePolicy
import com.growant.market.observation.port.MarketObservationRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class MarketObservationConfiguration {
    @Bean
    fun observationClockHealthPolicy(clock: Clock): ClockHealthPolicy = ClockHealthPolicy(clock)

    @Bean
    fun observationActivationPolicy(
        clock: Clock,
        clockHealthPolicy: ClockHealthPolicy,
    ): ObservationActivationPolicy = ObservationActivationPolicy(clock, clockHealthPolicy)

    @Bean
    fun reportedEventAgePolicy(): ReportedEventAgePolicy = ReportedEventAgePolicy()

    @Bean
    fun observationCleanupService(
        repository: MarketObservationRepository,
        clock: Clock,
    ): ObservationCleanupService = ObservationCleanupService(repository, clock)

    @Bean
    fun observationEvidenceManifestWriter(): ObservationEvidenceManifestWriter =
        ObservationEvidenceManifestWriter()

    @Bean
    fun observationEvidenceExportService(
        repository: MarketObservationRepository,
        writer: ObservationEvidenceManifestWriter,
        clock: Clock,
    ): ObservationEvidenceExportService = ObservationEvidenceExportService(repository, writer, clock)
}
