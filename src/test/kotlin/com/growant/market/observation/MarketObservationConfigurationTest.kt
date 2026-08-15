package com.growant.market.observation

import com.growant.market.observation.application.MarketObservationService
import com.growant.market.observation.application.ObservationCleanupService
import com.growant.market.observation.export.ObservationEvidenceExportService
import com.growant.market.observation.export.ObservationEvidenceManifestWriter
import com.growant.market.observation.policy.ClockHealthPolicy
import com.growant.market.observation.policy.ObservationActivationPolicy
import com.growant.market.observation.policy.ReportedEventAgePolicy
import com.growant.market.observation.port.MarketObservationRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.aop.support.AopUtils
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import java.time.Clock

class MarketObservationConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(
            MarketObservationConfiguration::class.java,
            MarketObservationService::class.java,
            TransactionTestConfiguration::class.java,
        )

    @Test
    fun `registers observation collaborators and applies the transactional service proxy`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(ClockHealthPolicy::class.java)
            assertThat(context).hasSingleBean(ObservationActivationPolicy::class.java)
            assertThat(context).hasSingleBean(ReportedEventAgePolicy::class.java)
            assertThat(context).hasSingleBean(ObservationCleanupService::class.java)
            assertThat(context).hasSingleBean(ObservationEvidenceManifestWriter::class.java)
            assertThat(context).hasSingleBean(ObservationEvidenceExportService::class.java)

            val service = context.getBean(MarketObservationService::class.java)
            assertThat(AopUtils.isAopProxy(service)).isTrue()
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    class TransactionTestConfiguration {
        @Bean
        fun observationRepository(): MarketObservationRepository = mock(MarketObservationRepository::class.java)

        @Bean
        fun observationClock(): Clock = Clock.systemUTC()

        @Bean
        fun observationTransactionManager(): PlatformTransactionManager =
            mock(PlatformTransactionManager::class.java)
    }
}
