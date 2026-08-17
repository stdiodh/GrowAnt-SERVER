package com.growant.market.observation.application

import com.growant.market.observation.CandleObservation
import com.growant.market.observation.FaultEvent
import com.growant.market.observation.MarketDataSemantics
import com.growant.market.observation.ObservationClockSample
import com.growant.market.observation.ObservationExpectedTicker
import com.growant.market.observation.ObservationRun
import com.growant.market.observation.ObservationRunState
import com.growant.market.observation.ObservationScope
import com.growant.market.observation.RestPollObservation
import com.growant.market.observation.TickObservation
import com.growant.market.observation.policy.ObservationActivationPolicy
import com.growant.market.observation.port.MarketObservationRepository
import com.growant.market.observation.port.ObservationAppendResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class MarketObservationService(
    private val repository: MarketObservationRepository,
    private val activationPolicy: ObservationActivationPolicy,
    private val clock: Clock,
) {
    fun plan(run: ObservationRun) {
        require(run.state == ObservationRunState.PLANNED) { "A new observation run must be planned" }
        require(run.startedAt == null && run.completedAt == null) {
            "A planned observation run must not have lifecycle timestamps"
        }
        require(run.retentionUntil.isAfter(run.createdAt)) {
            "retentionUntil must be after createdAt"
        }
        repository.createRun(run)
    }

    fun defineSemantics(semantics: MarketDataSemantics) {
        requireState(semantics.scope, ObservationRunState.PLANNED)
        repository.createSemantics(semantics)
    }

    fun defineExpectedTickers(expectedTickers: List<ObservationExpectedTicker>) {
        require(expectedTickers.isNotEmpty()) { "expectedTickers must not be empty" }
        val scope = expectedTickers.first().scope
        require(expectedTickers.all { it.scope == scope }) {
            "expectedTickers must share one observation scope"
        }
        requireState(scope, ObservationRunState.PLANNED)
        repository.createExpectedTickers(expectedTickers)
    }

    /**
     * Keeps the store's run-row lock until an unhealthy running sample has invalidated the run,
     * so completion cannot win between clock append and invalidation.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    fun recordClockSample(sample: ObservationClockSample) {
        val run = requiredRun(sample.scope)
        if (run.state != ObservationRunState.PLANNED && run.state != ObservationRunState.RUNNING) {
            throw ObservationStateException("Clock samples require a planned or running observation run")
        }
        val appendResult = repository.appendClockSample(sample)
        if (appendResult == ObservationAppendResult.REJECTED) {
            throw ObservationAppendRejectedException(ObservationRecordType.CLOCK_SAMPLE)
        }

        val currentRun = requiredRun(sample.scope)
        val clockRegressed = appendResult == ObservationAppendResult.APPENDED_CLOCK_REGRESSION
        if (!clockRegressed && currentRun.state != ObservationRunState.RUNNING) return

        val evaluatedAt = clock.instant()
        if (!clockRegressed && activationPolicy.assessClock(currentRun, sample, evaluatedAt).allowed) return

        val expectedState = currentRun.state
        if (expectedState != ObservationRunState.PLANNED && expectedState != ObservationRunState.RUNNING) {
            throw ObservationStateException("Clock samples require a planned or running observation run")
        }
        val invalidatedAt = maxOf(evaluatedAt, currentRun.startedAt ?: currentRun.createdAt)

        if (
            !repository.compareAndSetRunState(
                scope = sample.scope,
                expected = expectedState,
                updated = ObservationRunState.INVALID,
                changedAt = invalidatedAt,
            ) && requiredRun(sample.scope).state != ObservationRunState.INVALID
        ) {
            throw ObservationStateTransitionException(
                expected = expectedState,
                updated = ObservationRunState.INVALID,
            )
        }
    }

    /**
     * Locks the run and reads every activation input before the decision clock, so input
     * reads cannot cross the window boundary while retaining an earlier authorization time.
     */
    @Transactional
    fun activate(scope: ObservationScope) {
        val run = repository.lockRunForActivation(scope)
            ?: throw ObservationStateTransitionException(
                expected = ObservationRunState.PLANNED,
                updated = ObservationRunState.RUNNING,
            )
        val clockSample = repository.latestClockSample(scope)
        val semantics = repository.findSemantics(scope)
        val expectedTickers = repository.findExpectedTickers(scope)
        val activationAt = clock.instant()
        activationPolicy.requireActivatable(
            run = run,
            semantics = semantics,
            clockSample = clockSample,
            expectedTickers = expectedTickers,
            evaluatedAt = activationAt,
        )
        val expectedClockSampleSequence = checkNotNull(clockSample).sampleSequence
        if (!repository.activateRun(scope, expectedClockSampleSequence, activationAt)) {
            throw ObservationStateTransitionException(
                expected = ObservationRunState.PLANNED,
                updated = ObservationRunState.RUNNING,
            )
        }
    }

    /**
     * Reads the completion decision clock only after acquiring the run-row lock, so an
     * append wait cannot retain an earlier clock-health or retention decision.
     */
    @Transactional
    fun complete(scope: ObservationScope) {
        if (repository.lockRunForCompletion(scope) == null) {
            throw ObservationStateTransitionException(
                expected = ObservationRunState.RUNNING,
                updated = ObservationRunState.COMPLETED,
            )
        }
        transition(
            scope = scope,
            expected = ObservationRunState.RUNNING,
            updated = ObservationRunState.COMPLETED,
            changedAt = clock.instant(),
        )
    }

    fun invalidate(scope: ObservationScope) {
        val run = requiredRun(scope)
        val state = run.state
        if (state != ObservationRunState.PLANNED && state != ObservationRunState.RUNNING) {
            throw ObservationStateException("Only a planned or running observation run can be invalidated")
        }
        transition(
            scope = scope,
            expected = state,
            updated = ObservationRunState.INVALID,
            changedAt = maxOf(clock.instant(), run.startedAt ?: run.createdAt),
        )
    }

    fun append(observation: RestPollObservation) {
        requireRunning(observation.scope)
        requireAppended(repository.appendRestPoll(observation), ObservationRecordType.REST_POLL)
    }

    fun append(observation: TickObservation) {
        requireRunning(observation.scope)
        requireAppended(repository.appendTick(observation), ObservationRecordType.TICK)
    }

    fun append(observation: CandleObservation) {
        requireRunning(observation.scope)
        requireAppended(repository.appendCandle(observation), ObservationRecordType.CANDLE)
    }

    fun append(event: FaultEvent) {
        requireRunning(event.scope)
        requireAppended(repository.appendFaultEvent(event), ObservationRecordType.FAULT_EVENT)
    }

    private fun transition(
        scope: ObservationScope,
        expected: ObservationRunState,
        updated: ObservationRunState,
        changedAt: Instant = clock.instant(),
    ) {
        if (!repository.compareAndSetRunState(scope, expected, updated, changedAt)) {
            throw ObservationStateTransitionException(expected, updated)
        }
    }

    private fun requireRunning(scope: ObservationScope) {
        requireState(scope, ObservationRunState.RUNNING)
    }

    private fun requireState(
        scope: ObservationScope,
        expected: ObservationRunState,
    ) {
        val state = requiredRun(scope).state
        if (state != expected) {
            throw ObservationStateException("Observation run state must be $expected")
        }
    }

    private fun requiredRun(scope: ObservationScope): ObservationRun =
        repository.findRun(scope) ?: throw ObservationRunNotFoundException()

    private fun requireAppended(
        result: ObservationAppendResult,
        recordType: ObservationRecordType,
    ) {
        if (result != ObservationAppendResult.APPENDED) {
            throw ObservationAppendRejectedException(recordType)
        }
    }
}

enum class ObservationRecordType {
    CLOCK_SAMPLE,
    REST_POLL,
    TICK,
    CANDLE,
    FAULT_EVENT,
}

class ObservationRunNotFoundException : NoSuchElementException("Observation run was not found")

class ObservationStateException(message: String) : IllegalStateException(message)

class ObservationStateTransitionException(
    val expected: ObservationRunState,
    val updated: ObservationRunState,
) : IllegalStateException("Observation run state transition failed: $expected->$updated")

class ObservationAppendRejectedException(
    val recordType: ObservationRecordType,
) : IllegalStateException("Observation record append was rejected: $recordType")
