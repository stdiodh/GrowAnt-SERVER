package com.growant.market.observation.application

import com.growant.market.observation.ObservationCleanupAudit
import com.growant.market.observation.ObservationCleanupResult
import com.growant.market.observation.ObservationRunState
import com.growant.market.observation.ObservationTestFixtures
import com.growant.market.observation.port.MarketObservationRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

class ObservationCleanupServiceTest {
    private val repository = mock(MarketObservationRepository::class.java)
    private val now = ObservationTestFixtures.baseTime
    private val cleanupId = UUID.fromString("50000000-0000-0000-0000-000000000001")
    private val service = ObservationCleanupService(
        repository = repository,
        clock = Clock.fixed(now, ZoneOffset.UTC),
        cleanupIdFactory = { cleanupId },
    )

    @Test
    fun `delegates an expired running scope to atomic invalidation and cleanup`() {
        val runningScope = ObservationTestFixtures.scope()
        val audit = cleanupAudit(runningScope)
        given(repository.findExpiredScopes(now, 10)).willReturn(listOf(runningScope))
        given(repository.cleanupTerminalRun(runningScope, cleanupId, now)).willReturn(audit)

        assertThat(service.cleanupExpired(limit = 10)).containsExactly(audit)

        verify(repository).cleanupTerminalRun(runningScope, cleanupId, now)
    }

    private fun cleanupAudit(scope: com.growant.market.observation.ObservationScope) = ObservationCleanupAudit(
        cleanupId = cleanupId,
        scope = scope,
        result = ObservationCleanupResult.DELETED,
        terminalState = ObservationRunState.INVALID,
        retentionUntil = now.minusSeconds(1),
        requestedAt = now,
        completedAt = now,
        semanticsDeleted = 1,
        expectedTickersDeleted = 1,
        clockSamplesDeleted = 1,
        restPollsDeleted = 1,
        ticksDeleted = 1,
        candlesDeleted = 1,
        faultEventsDeleted = 1,
    )
}
