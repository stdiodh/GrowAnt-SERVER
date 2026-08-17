package com.growant.market.observation.application

import com.growant.market.observation.ObservationCleanupAudit
import com.growant.market.observation.port.MarketObservationRepository
import java.time.Clock
import java.util.UUID

class ObservationCleanupService(
    private val repository: MarketObservationRepository,
    private val clock: Clock,
    private val cleanupIdFactory: () -> UUID = UUID::randomUUID,
) {
    fun cleanupExpired(limit: Int): List<ObservationCleanupAudit> {
        require(limit > 0) { "limit must be positive" }

        val requestedAt = clock.instant()
        return repository.findExpiredScopes(requestedAt, limit).map { scope ->
            repository.cleanupTerminalRun(
                scope = scope,
                cleanupId = cleanupIdFactory(),
                requestedAt = requestedAt,
            )
        }
    }
}
