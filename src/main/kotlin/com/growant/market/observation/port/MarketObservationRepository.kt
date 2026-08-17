package com.growant.market.observation.port

import com.growant.market.observation.CandleObservation
import com.growant.market.observation.FaultEvent
import com.growant.market.observation.MarketDataSemantics
import com.growant.market.observation.ObservationCleanupAudit
import com.growant.market.observation.ObservationClockSample
import com.growant.market.observation.ObservationEvidenceBundle
import com.growant.market.observation.ObservationEvidenceSnapshot
import com.growant.market.observation.ObservationExpectedTicker
import com.growant.market.observation.ObservationRun
import com.growant.market.observation.ObservationRunState
import com.growant.market.observation.ObservationScope
import com.growant.market.observation.RestPollObservation
import com.growant.market.observation.TickObservation
import java.time.Instant
import java.util.UUID

interface MarketObservationRepository {
    fun createRun(run: ObservationRun)

    fun findRun(scope: ObservationScope): ObservationRun?

    fun createExpectedTickers(expectedTickers: List<ObservationExpectedTicker>)

    fun findExpectedTickers(scope: ObservationScope): List<ObservationExpectedTicker>

    /**
     * Acquires the run-row activation lock. Callers must keep an outer transaction open
     * while evaluating the decision time and invoking [activateRun].
     */
    fun lockRunForActivation(scope: ObservationScope): ObservationRun?

    fun activateRun(
        scope: ObservationScope,
        expectedClockSampleSequence: Long,
        changedAt: Instant,
    ): Boolean

    /**
     * Acquires the running run-row completion lock. Callers must keep an outer transaction
     * open while reading the completion decision time and invoking [compareAndSetRunState].
     */
    fun lockRunForCompletion(scope: ObservationScope): ObservationRun?

    fun compareAndSetRunState(
        scope: ObservationScope,
        expected: ObservationRunState,
        updated: ObservationRunState,
        changedAt: Instant,
    ): Boolean

    fun createSemantics(semantics: MarketDataSemantics)

    fun findSemantics(scope: ObservationScope): MarketDataSemantics?

    fun appendClockSample(sample: ObservationClockSample): ObservationAppendResult

    fun findClockSample(
        scope: ObservationScope,
        sampleSequence: Long,
    ): ObservationClockSample?

    fun latestClockSample(scope: ObservationScope): ObservationClockSample?

    fun appendRestPoll(observation: RestPollObservation): ObservationAppendResult

    fun appendTick(observation: TickObservation): ObservationAppendResult

    fun appendCandle(observation: CandleObservation): ObservationAppendResult

    fun appendFaultEvent(event: FaultEvent): ObservationAppendResult

    fun evidenceSnapshot(scope: ObservationScope): ObservationEvidenceSnapshot?

    fun evidenceBundle(scope: ObservationScope): ObservationEvidenceBundle?

    fun findExpiredScopes(
        expiredAt: Instant,
        limit: Int,
    ): List<ObservationScope>

    fun cleanupTerminalRun(
        scope: ObservationScope,
        cleanupId: UUID,
        requestedAt: Instant,
    ): ObservationCleanupAudit
}

enum class ObservationAppendResult {
    APPENDED,
    APPENDED_CLOCK_REGRESSION,
    REJECTED,
}
