package com.growant.market.observation.application

import com.growant.market.observation.ObservationRun
import com.growant.market.observation.ObservationRunState
import com.growant.market.observation.ObservationClockSource
import com.growant.market.observation.ObservationOrigin
import com.growant.market.observation.ObservationTestFixtures
import com.growant.market.observation.RightsDecision
import com.growant.market.observation.policy.ClockHealthPolicy
import com.growant.market.observation.policy.ObservationActivationFailure
import com.growant.market.observation.policy.ObservationActivationPolicy
import com.growant.market.observation.policy.ObservationActivationRejectedException
import com.growant.market.observation.port.MarketObservationRepository
import com.growant.market.observation.port.ObservationAppendResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MarketObservationServiceTest {
    private val repository = mock(MarketObservationRepository::class.java)
    private val clock = Clock.fixed(ObservationTestFixtures.baseTime, ZoneOffset.UTC)
    private val service = MarketObservationService(
        repository = repository,
        activationPolicy = ObservationActivationPolicy(
            clock = clock,
            clockHealthPolicy = ClockHealthPolicy(clock, Duration.ofMinutes(1)),
        ),
        clock = clock,
    )

    @Test
    fun `activation refuses denied storage rights without changing state`() {
        val run = activatableRun().copy(
            rights = ObservationTestFixtures.rights(storage = RightsDecision.DENIED),
        )
        prepareActivation(run)

        val exception = assertThrows<ObservationActivationRejectedException> { service.activate(run.scope) }

        assertThat(exception.failures)
            .containsExactly(ObservationActivationFailure.STORAGE_NOT_ALLOWED)
        verify(repository, never()).activateRun(run.scope, 1, ObservationTestFixtures.baseTime)
    }

    @Test
    fun `activation refuses provider data until the rights registry is verified`() {
        val run = activatableRun().copy(origin = ObservationOrigin.PROVIDER)
        prepareActivation(run)
        given(repository.latestClockSample(run.scope)).willReturn(
            ObservationTestFixtures.clockSample(run.scope).copy(source = ObservationClockSource.NTP),
        )

        val exception = assertThrows<ObservationActivationRejectedException> { service.activate(run.scope) }

        assertThat(exception.failures)
            .containsExactly(ObservationActivationFailure.RIGHTS_REGISTRY_UNVERIFIED)
        verify(repository, never()).activateRun(run.scope, 1, ObservationTestFixtures.baseTime)
    }

    @Test
    fun `activation uses the validated clock sequence in the atomic repository transition`() {
        val run = activatableRun()
        prepareActivation(run)
        given(repository.activateRun(run.scope, 1, ObservationTestFixtures.baseTime)).willReturn(true)

        service.activate(run.scope)

        verify(repository).activateRun(run.scope, 1, ObservationTestFixtures.baseTime)
        verify(repository, never()).compareAndSetRunState(
            run.scope,
            ObservationRunState.PLANNED,
            ObservationRunState.RUNNING,
            ObservationTestFixtures.baseTime,
        )
    }

    @Test
    fun `activation reads its decision time only after locking the run`() {
        val run = activatableRun()
        val locked = AtomicBoolean(false)
        val lockAwareClock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId): Clock = this

            override fun instant(): Instant {
                check(locked.get()) { "decision clock was read before the run lock" }
                return ObservationTestFixtures.baseTime
            }
        }
        val lockAwareService = MarketObservationService(
            repository = repository,
            activationPolicy = ObservationActivationPolicy(
                clock = lockAwareClock,
                clockHealthPolicy = ClockHealthPolicy(lockAwareClock, Duration.ofMinutes(1)),
            ),
            clock = lockAwareClock,
        )
        given(repository.lockRunForActivation(run.scope)).willAnswer {
            locked.set(true)
            run
        }
        given(repository.findSemantics(run.scope)).willReturn(ObservationTestFixtures.semantics(run.scope))
        given(repository.latestClockSample(run.scope)).willReturn(ObservationTestFixtures.clockSample(run.scope))
        given(repository.findExpectedTickers(run.scope))
            .willReturn(ObservationTestFixtures.expectedTickers(run.scope))
        given(repository.activateRun(run.scope, 1, ObservationTestFixtures.baseTime)).willReturn(true)

        lockAwareService.activate(run.scope)

        verify(repository).activateRun(run.scope, 1, ObservationTestFixtures.baseTime)
    }

    @Test
    fun `activation refuses when input reads cross the observation window start`() {
        val run = activatableRun()
        val currentTime = AtomicReference(ObservationTestFixtures.baseTime)
        val advancingClock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId): Clock = this

            override fun instant(): Instant = currentTime.get()
        }
        val advancingClockService = MarketObservationService(
            repository = repository,
            activationPolicy = ObservationActivationPolicy(
                clock = advancingClock,
                clockHealthPolicy = ClockHealthPolicy(advancingClock, Duration.ofMinutes(1)),
            ),
            clock = advancingClock,
        )
        given(repository.lockRunForActivation(run.scope)).willReturn(run)
        given(repository.latestClockSample(run.scope)).willReturn(ObservationTestFixtures.clockSample(run.scope))
        given(repository.findSemantics(run.scope)).willReturn(ObservationTestFixtures.semantics(run.scope))
        given(repository.findExpectedTickers(run.scope)).willAnswer {
            currentTime.set(run.windowStart.plusNanos(1))
            ObservationTestFixtures.expectedTickers(run.scope)
        }

        val exception = assertThrows<ObservationActivationRejectedException> {
            advancingClockService.activate(run.scope)
        }

        assertThat(exception.failures)
            .containsExactly(ObservationActivationFailure.OBSERVATION_WINDOW_ALREADY_STARTED)
        verify(repository, never()).activateRun(run.scope, 1, run.windowStart.plusNanos(1))
    }

    @Test
    fun `reports an atomic activation race without exposing the run scope`() {
        val run = activatableRun()
        prepareActivation(run)
        given(repository.activateRun(run.scope, 1, ObservationTestFixtures.baseTime)).willReturn(false)

        val exception = assertThrows<ObservationStateTransitionException> { service.activate(run.scope) }

        assertThat(exception.expected).isEqualTo(ObservationRunState.PLANNED)
        assertThat(exception.updated).isEqualTo(ObservationRunState.RUNNING)
        assertThat(exception.message)
            .doesNotContain(run.scope.provider)
            .doesNotContain(run.scope.runId.toString())
    }

    @Test
    fun `completion reads its decision time only after locking the running run`() {
        val run = runningRun()
        val completionAt = run.windowEnd
        val currentTime = AtomicReference(ObservationTestFixtures.baseTime)
        val advancingClock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId): Clock = this

            override fun instant(): Instant = currentTime.get()
        }
        val advancingClockService = serviceWithClock(advancingClock)
        given(repository.lockRunForCompletion(run.scope)).willAnswer {
            currentTime.set(completionAt)
            run
        }
        given(
            repository.compareAndSetRunState(
                run.scope,
                ObservationRunState.RUNNING,
                ObservationRunState.COMPLETED,
                completionAt,
            ),
        ).willReturn(true)

        advancingClockService.complete(run.scope)

        verify(repository).lockRunForCompletion(run.scope)
        verify(repository).compareAndSetRunState(
            run.scope,
            ObservationRunState.RUNNING,
            ObservationRunState.COMPLETED,
            completionAt,
        )
    }

    @Test
    fun `defines the immutable expected ticker set only for a planned run`() {
        val run = ObservationTestFixtures.run()
        val tickers = ObservationTestFixtures.expectedTickers(run.scope)
        given(repository.findRun(run.scope)).willReturn(run)

        service.defineExpectedTickers(tickers)

        verify(repository).createExpectedTickers(tickers)
    }

    @Test
    fun `does not append an observation unless its run is running`() {
        val run = ObservationTestFixtures.run(state = ObservationRunState.PLANNED)
        val tick = ObservationTestFixtures.tick(run.scope)
        given(repository.findRun(run.scope)).willReturn(run)

        assertThatThrownBy { service.append(tick) }
            .isInstanceOf(ObservationStateException::class.java)
            .hasMessage("Observation run state must be RUNNING")
        verify(repository, never()).appendTick(tick)
    }

    @Test
    fun `turns a repository rights rejection into an explicit append failure`() {
        val run = runningRun()
        val tick = ObservationTestFixtures.tick(run.scope)
        given(repository.findRun(run.scope)).willReturn(run)
        given(repository.appendTick(tick)).willReturn(ObservationAppendResult.REJECTED)

        val exception = assertThrows<ObservationAppendRejectedException> { service.append(tick) }

        assertThat(exception.recordType).isEqualTo(ObservationRecordType.TICK)
    }

    @Test
    fun `appends a tick for a running run with an accepted repository write`() {
        val run = runningRun()
        val tick = ObservationTestFixtures.tick(run.scope)
        given(repository.findRun(run.scope)).willReturn(run)
        given(repository.appendTick(tick)).willReturn(ObservationAppendResult.APPENDED)

        service.append(tick)

        verify(repository).appendTick(tick)
    }

    @Test
    fun `stores a bad running clock sample then invalidates the run`() {
        val before = runningRun()
        val sample = ObservationTestFixtures.clockSample(before.scope, sampleSequence = 2).copy(
            synchronized = false,
        )
        val afterAppend = before.copy(latestClockSampleSequence = 2)
        given(repository.findRun(before.scope)).willReturn(before, afterAppend)
        given(repository.appendClockSample(sample)).willReturn(ObservationAppendResult.APPENDED)
        given(
            repository.compareAndSetRunState(
                before.scope,
                ObservationRunState.RUNNING,
                ObservationRunState.INVALID,
                ObservationTestFixtures.baseTime,
            ),
        ).willReturn(true)

        service.recordClockSample(sample)

        verify(repository).appendClockSample(sample)
        verify(repository).compareAndSetRunState(
            before.scope,
            ObservationRunState.RUNNING,
            ObservationRunState.INVALID,
            ObservationTestFixtures.baseTime,
        )
    }

    @Test
    fun `clamps invalidation time after preserving an actual backward clock sample`() {
        val run = runningRun()
        val regressedSample = ObservationTestFixtures.clockSample(run.scope, sampleSequence = 2).copy(
            sampledAt = ObservationTestFixtures.baseTime.minusMillis(1),
        )
        val backwardClock = Clock.fixed(ObservationTestFixtures.baseTime.minusSeconds(10), ZoneOffset.UTC)
        val backwardClockService = MarketObservationService(
            repository = repository,
            activationPolicy = ObservationActivationPolicy(
                clock = backwardClock,
                clockHealthPolicy = ClockHealthPolicy(backwardClock, Duration.ofMinutes(1)),
            ),
            clock = backwardClock,
        )
        given(repository.findRun(run.scope)).willReturn(run, run)
        given(repository.appendClockSample(regressedSample))
            .willReturn(ObservationAppendResult.APPENDED_CLOCK_REGRESSION)
        given(
            repository.compareAndSetRunState(
                run.scope,
                ObservationRunState.RUNNING,
                ObservationRunState.INVALID,
                ObservationTestFixtures.baseTime,
            ),
        ).willReturn(true)

        backwardClockService.recordClockSample(regressedSample)

        verify(repository).appendClockSample(regressedSample)
        verify(repository).compareAndSetRunState(
            run.scope,
            ObservationRunState.RUNNING,
            ObservationRunState.INVALID,
            ObservationTestFixtures.baseTime,
        )
    }

    @Test
    fun `manual invalidation clamps a planned run to its creation time`() {
        val run = ObservationTestFixtures.run(createdAt = ObservationTestFixtures.baseTime)
        val backwardService = serviceWithClock(
            Clock.fixed(ObservationTestFixtures.baseTime.minusSeconds(10), ZoneOffset.UTC),
        )
        given(repository.findRun(run.scope)).willReturn(run)
        given(
            repository.compareAndSetRunState(
                run.scope,
                ObservationRunState.PLANNED,
                ObservationRunState.INVALID,
                run.createdAt,
            ),
        ).willReturn(true)

        backwardService.invalidate(run.scope)

        verify(repository).compareAndSetRunState(
            run.scope,
            ObservationRunState.PLANNED,
            ObservationRunState.INVALID,
            run.createdAt,
        )
    }

    @Test
    fun `manual invalidation clamps a running run to its start time`() {
        val run = ObservationTestFixtures.run(
            state = ObservationRunState.RUNNING,
            createdAt = ObservationTestFixtures.baseTime.minusSeconds(10),
            windowStart = ObservationTestFixtures.baseTime.plusSeconds(60),
            windowEnd = ObservationTestFixtures.baseTime.plusSeconds(180),
            startedAt = ObservationTestFixtures.baseTime,
        )
        val backwardService = serviceWithClock(
            Clock.fixed(ObservationTestFixtures.baseTime.minusSeconds(20), ZoneOffset.UTC),
        )
        given(repository.findRun(run.scope)).willReturn(run)
        given(
            repository.compareAndSetRunState(
                run.scope,
                ObservationRunState.RUNNING,
                ObservationRunState.INVALID,
                run.startedAt!!,
            ),
        ).willReturn(true)

        backwardService.invalidate(run.scope)

        verify(repository).compareAndSetRunState(
            run.scope,
            ObservationRunState.RUNNING,
            ObservationRunState.INVALID,
            run.startedAt!!,
        )
    }

    private fun prepareActivation(run: ObservationRun) {
        given(repository.lockRunForActivation(run.scope)).willReturn(run)
        given(repository.findSemantics(run.scope)).willReturn(ObservationTestFixtures.semantics(run.scope))
        given(repository.latestClockSample(run.scope)).willReturn(ObservationTestFixtures.clockSample(run.scope))
        given(repository.findExpectedTickers(run.scope)).willReturn(ObservationTestFixtures.expectedTickers(run.scope))
    }

    private fun activatableRun() = ObservationTestFixtures.run().copy(latestClockSampleSequence = 1)

    private fun runningRun() = ObservationTestFixtures.run(
        state = ObservationRunState.RUNNING,
        startedAt = ObservationTestFixtures.baseTime,
    )

    private fun serviceWithClock(customClock: Clock) = MarketObservationService(
        repository = repository,
        activationPolicy = ObservationActivationPolicy(
            clock = customClock,
            clockHealthPolicy = ClockHealthPolicy(customClock, Duration.ofMinutes(1)),
        ),
        clock = customClock,
    )
}
