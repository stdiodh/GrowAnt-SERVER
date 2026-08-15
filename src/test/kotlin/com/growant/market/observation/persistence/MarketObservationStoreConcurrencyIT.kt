package com.growant.market.observation.persistence

import com.growant.market.observation.ObservationRunState
import com.growant.market.observation.ObservationScope
import com.growant.market.observation.ObservationTestFixtures
import com.growant.market.observation.application.MarketObservationService
import com.growant.market.observation.application.ObservationStateTransitionException
import com.growant.market.observation.policy.ClockHealthPolicy
import com.growant.market.observation.policy.ObservationActivationPolicy
import com.growant.market.observation.port.ObservationAppendResult
import com.growant.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

class MarketObservationStoreConcurrencyIT(
    @Autowired private val store: MarketObservationStore,
    @Autowired private val transactionManager: PlatformTransactionManager,
    @Autowired private val observationServiceProvider: ObjectProvider<MarketObservationService>,
) : PostgresIntegrationTest() {
    @Test
    fun `terminal update blocks while production append holds the run share lock`() {
        val scope = ObservationScope(
            runId = UUID.fromString("54000000-0000-0000-0000-000000000001"),
            provider = "kis",
        )
        createRunningRun(scope)
        val tick = ObservationTestFixtures.tick(scope)
        val executor = Executors.newFixedThreadPool(2)
        val appendInserted = CountDownLatch(1)
        val releaseAppendCommit = CountDownLatch(1)
        val transitionAttempted = CountDownLatch(1)

        try {
            val append = executor.submit<ObservationAppendResult> {
                checkNotNull(
                    TransactionTemplate(transactionManager).execute {
                        val result = store.appendTick(tick)
                        assertThat(result).isEqualTo(ObservationAppendResult.APPENDED)
                        appendInserted.countDown()
                        assertThat(releaseAppendCommit.await(5, TimeUnit.SECONDS)).isTrue()
                        result
                    },
                )
            }

            assertThat(appendInserted.await(5, TimeUnit.SECONDS)).isTrue()
            val transition = executor.submit<Boolean> {
                transitionAttempted.countDown()
                store.compareAndSetRunState(
                    scope = scope,
                    expected = ObservationRunState.RUNNING,
                    updated = ObservationRunState.INVALID,
                    changedAt = ObservationTestFixtures.baseTime.plusSeconds(12),
                )
            }

            assertThat(transitionAttempted.await(5, TimeUnit.SECONDS)).isTrue()
            assertThatThrownBy { transition.get(250, TimeUnit.MILLISECONDS) }
                .isInstanceOf(TimeoutException::class.java)

            releaseAppendCommit.countDown()
            assertThat(append.get(5, TimeUnit.SECONDS)).isEqualTo(ObservationAppendResult.APPENDED)
            assertThat(transition.get(5, TimeUnit.SECONDS)).isTrue()

            assertThat(store.findRun(scope)!!.state).isEqualTo(ObservationRunState.INVALID)
            assertThat(store.evidenceSnapshot(scope)!!.tickCount).isEqualTo(1)
        } finally {
            releaseAppendCommit.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `production append rechecks state after terminal transaction commits`() {
        val scope = ObservationScope(
            runId = UUID.fromString("54000000-0000-0000-0000-000000000002"),
            provider = "kis",
        )
        createRunningRun(scope)
        val tick = ObservationTestFixtures.tick(scope)
        val executor = Executors.newFixedThreadPool(2)
        val transitionUpdated = CountDownLatch(1)
        val releaseTransitionCommit = CountDownLatch(1)
        val appendAttempted = CountDownLatch(1)

        try {
            val transition = executor.submit<Boolean> {
                checkNotNull(
                    TransactionTemplate(transactionManager).execute {
                        val result = store.compareAndSetRunState(
                            scope = scope,
                            expected = ObservationRunState.RUNNING,
                            updated = ObservationRunState.INVALID,
                            changedAt = ObservationTestFixtures.baseTime.plusSeconds(12),
                        )
                        assertThat(result).isTrue()
                        transitionUpdated.countDown()
                        assertThat(releaseTransitionCommit.await(5, TimeUnit.SECONDS)).isTrue()
                        result
                    },
                )
            }

            assertThat(transitionUpdated.await(5, TimeUnit.SECONDS)).isTrue()
            val append = executor.submit<ObservationAppendResult> {
                appendAttempted.countDown()
                store.appendTick(tick)
            }

            assertThat(appendAttempted.await(5, TimeUnit.SECONDS)).isTrue()
            assertThatThrownBy { append.get(250, TimeUnit.MILLISECONDS) }
                .isInstanceOf(TimeoutException::class.java)

            releaseTransitionCommit.countDown()
            assertThat(transition.get(5, TimeUnit.SECONDS)).isTrue()
            assertThat(append.get(5, TimeUnit.SECONDS)).isEqualTo(ObservationAppendResult.REJECTED)

            assertThat(store.findRun(scope)!!.state).isEqualTo(ObservationRunState.INVALID)
            assertThat(store.evidenceSnapshot(scope)!!.tickCount).isZero()
            assertThat(store.appendTick(tick.copy(localReceiveSequence = 2)))
                .isEqualTo(ObservationAppendResult.REJECTED)
            assertThat(store.evidenceSnapshot(scope)!!.tickCount).isZero()
        } finally {
            releaseTransitionCommit.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `completion rechecks committed REST counts after waiting for an append`() {
        val scope = ObservationScope(
            runId = UUID.fromString("54000000-0000-0000-0000-000000000003"),
            provider = "toss",
        )
        createRunningRun(scope)
        assertThat(
            store.appendClockSample(
                ObservationTestFixtures.clockSample(scope, sampleSequence = 2).copy(
                    sampledAt = ObservationTestFixtures.baseTime.plusSeconds(119),
                ),
            ),
        ).isEqualTo(ObservationAppendResult.APPENDED)

        val executor = Executors.newFixedThreadPool(2)
        val pollInserted = CountDownLatch(1)
        val releasePollCommit = CountDownLatch(1)
        val completionAttempted = CountDownLatch(1)

        try {
            val append = executor.submit<ObservationAppendResult> {
                checkNotNull(
                    TransactionTemplate(transactionManager).execute {
                        val result = store.appendRestPoll(ObservationTestFixtures.restPoll(scope))
                        assertThat(result).isEqualTo(ObservationAppendResult.APPENDED)
                        pollInserted.countDown()
                        assertThat(releasePollCommit.await(5, TimeUnit.SECONDS)).isTrue()
                        result
                    },
                )
            }

            assertThat(pollInserted.await(5, TimeUnit.SECONDS)).isTrue()
            val completion = executor.submit<Boolean> {
                completionAttempted.countDown()
                store.compareAndSetRunState(
                    scope = scope,
                    expected = ObservationRunState.RUNNING,
                    updated = ObservationRunState.COMPLETED,
                    changedAt = ObservationTestFixtures.baseTime.plusSeconds(120),
                )
            }

            assertThat(completionAttempted.await(5, TimeUnit.SECONDS)).isTrue()
            assertThatThrownBy { completion.get(250, TimeUnit.MILLISECONDS) }
                .isInstanceOf(TimeoutException::class.java)

            releasePollCommit.countDown()
            assertThat(append.get(5, TimeUnit.SECONDS)).isEqualTo(ObservationAppendResult.APPENDED)
            assertThat(completion.get(5, TimeUnit.SECONDS)).isFalse()
            assertThat(store.findRun(scope)!!.state).isEqualTo(ObservationRunState.RUNNING)
            assertThat(store.evidenceSnapshot(scope)!!.restPollCount).isEqualTo(1)
            assertThat(store.evidenceSnapshot(scope)!!.candleCount).isZero()

            assertThat(
                store.appendCandle(
                    ObservationTestFixtures.candle(scope).copy(
                        observedAt = ObservationTestFixtures.baseTime.plusSeconds(119),
                    ),
                ),
            ).isEqualTo(ObservationAppendResult.APPENDED)
            assertThat(
                store.compareAndSetRunState(
                    scope = scope,
                    expected = ObservationRunState.RUNNING,
                    updated = ObservationRunState.COMPLETED,
                    changedAt = ObservationTestFixtures.baseTime.plusSeconds(120),
                ),
            ).isTrue()
        } finally {
            releasePollCommit.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `completion rechecks the committed terminal REST page after waiting for its append`() {
        val scope = ObservationScope(
            runId = UUID.fromString("54000000-0000-0000-0000-000000000007"),
            provider = "toss",
        )
        createRunningRun(scope)
        val rootRequestId = UUID.fromString("55000000-0000-0000-0000-000000000001")
        val root = ObservationTestFixtures.restPoll(scope, rootRequestId).copy(
            nextCursor = "terminal-page-cursor",
            pollTerminal = false,
            returnedCandleCount = 0,
            eligibleCandleCount = 0,
        )
        assertThat(store.appendRestPoll(root)).isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            store.appendClockSample(
                ObservationTestFixtures.clockSample(scope, sampleSequence = 2).copy(
                    sampledAt = ObservationTestFixtures.baseTime.plusSeconds(119),
                ),
            ),
        ).isEqualTo(ObservationAppendResult.APPENDED)

        val terminalPage = root.copy(
            requestId = UUID.fromString("55000000-0000-0000-0000-000000000002"),
            pollRunId = rootRequestId,
            pageOrdinal = 1,
            requestCursor = "terminal-page-cursor",
            nextCursor = null,
            pollTerminal = true,
            requestStartedAt = ObservationTestFixtures.baseTime.plusSeconds(3),
            observedAt = ObservationTestFixtures.baseTime.plusSeconds(4),
            normalizedAt = ObservationTestFixtures.baseTime.plusSeconds(4),
        )
        val executor = Executors.newFixedThreadPool(2)
        val pageInserted = CountDownLatch(1)
        val releasePageCommit = CountDownLatch(1)
        val completionAttempted = CountDownLatch(1)

        try {
            val append = executor.submit<ObservationAppendResult> {
                checkNotNull(
                    TransactionTemplate(transactionManager).execute {
                        val result = store.appendRestPoll(terminalPage)
                        assertThat(result).isEqualTo(ObservationAppendResult.APPENDED)
                        pageInserted.countDown()
                        assertThat(releasePageCommit.await(5, TimeUnit.SECONDS)).isTrue()
                        result
                    },
                )
            }

            assertThat(pageInserted.await(5, TimeUnit.SECONDS)).isTrue()
            val completion = executor.submit<Boolean> {
                completionAttempted.countDown()
                store.compareAndSetRunState(
                    scope = scope,
                    expected = ObservationRunState.RUNNING,
                    updated = ObservationRunState.COMPLETED,
                    changedAt = ObservationTestFixtures.baseTime.plusSeconds(120),
                )
            }

            assertThat(completionAttempted.await(5, TimeUnit.SECONDS)).isTrue()
            assertThatThrownBy { completion.get(250, TimeUnit.MILLISECONDS) }
                .isInstanceOf(TimeoutException::class.java)

            releasePageCommit.countDown()
            assertThat(append.get(5, TimeUnit.SECONDS)).isEqualTo(ObservationAppendResult.APPENDED)
            assertThat(completion.get(5, TimeUnit.SECONDS)).isTrue()
            assertThat(store.findRun(scope)!!.state).isEqualTo(ObservationRunState.COMPLETED)
            assertThat(store.evidenceSnapshot(scope)!!.restPollCount).isEqualTo(2)
            assertThat(store.evidenceSnapshot(scope)!!.restPollRunCount).isEqualTo(1)
        } finally {
            releasePageCommit.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `completion rejects an expired decision after waiting for an append lock`() {
        val scope = ObservationScope(
            runId = UUID.fromString("54000000-0000-0000-0000-000000000006"),
            provider = "kis",
        )
        val run = ObservationTestFixtures.run(
            scope = scope,
            retentionUntil = ObservationTestFixtures.baseTime.plusSeconds(121),
        )
        store.createRun(run)
        store.createExpectedTickers(ObservationTestFixtures.expectedTickers(scope))
        store.createSemantics(ObservationTestFixtures.semantics(scope))
        assertThat(store.appendClockSample(ObservationTestFixtures.clockSample(scope)))
            .isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            store.activateRun(
                scope,
                expectedClockSampleSequence = 1,
                changedAt = ObservationTestFixtures.baseTime.plusSeconds(1),
            ),
        ).isTrue()
        assertThat(
            store.appendClockSample(
                ObservationTestFixtures.clockSample(scope, sampleSequence = 2).copy(
                    sampledAt = ObservationTestFixtures.baseTime.plusSeconds(119),
                ),
            ),
        ).isEqualTo(ObservationAppendResult.APPENDED)

        val currentTime = AtomicReference(ObservationTestFixtures.baseTime.plusSeconds(120))
        val advancingClock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId): Clock = this

            override fun instant(): Instant = currentTime.get()
        }
        val completionService = MarketObservationService(
            repository = store,
            activationPolicy = ObservationActivationPolicy(
                clock = advancingClock,
                clockHealthPolicy = ClockHealthPolicy(advancingClock),
            ),
            clock = advancingClock,
        )
        val executor = Executors.newFixedThreadPool(2)
        val appendInserted = CountDownLatch(1)
        val releaseAppendCommit = CountDownLatch(1)
        val completionAttempted = CountDownLatch(1)

        try {
            val append = executor.submit<ObservationAppendResult> {
                checkNotNull(
                    TransactionTemplate(transactionManager).execute {
                        val result = store.appendTick(ObservationTestFixtures.tick(scope))
                        assertThat(result).isEqualTo(ObservationAppendResult.APPENDED)
                        appendInserted.countDown()
                        assertThat(releaseAppendCommit.await(5, TimeUnit.SECONDS)).isTrue()
                        result
                    },
                )
            }

            assertThat(appendInserted.await(5, TimeUnit.SECONDS)).isTrue()
            val completion = executor.submit<Boolean> {
                completionAttempted.countDown()
                try {
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        completionService.complete(scope)
                    }
                    false
                } catch (_: ObservationStateTransitionException) {
                    true
                }
            }

            assertThat(completionAttempted.await(5, TimeUnit.SECONDS)).isTrue()
            assertThatThrownBy { completion.get(250, TimeUnit.MILLISECONDS) }
                .isInstanceOf(TimeoutException::class.java)

            currentTime.set(ObservationTestFixtures.baseTime.plusSeconds(122))
            releaseAppendCommit.countDown()
            assertThat(append.get(5, TimeUnit.SECONDS)).isEqualTo(ObservationAppendResult.APPENDED)
            assertThat(completion.get(5, TimeUnit.SECONDS)).isTrue()
            assertThat(store.findRun(scope)!!.state).isEqualTo(ObservationRunState.RUNNING)
        } finally {
            releaseAppendCommit.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `tick append uses the newly committed clock sample after waiting for its transaction`() {
        val scope = ObservationScope(
            runId = UUID.fromString("54000000-0000-0000-0000-000000000004"),
            provider = "kis",
        )
        createRunningRun(scope)
        val executor = Executors.newFixedThreadPool(2)
        val clockAppended = CountDownLatch(1)
        val releaseClockCommit = CountDownLatch(1)
        val tickAttempted = CountDownLatch(1)

        try {
            val clockAppend = executor.submit<ObservationAppendResult> {
                checkNotNull(
                    TransactionTemplate(transactionManager).execute {
                        val result = store.appendClockSample(
                            ObservationTestFixtures.clockSample(scope, sampleSequence = 2).copy(
                                sampledAt = ObservationTestFixtures.baseTime.plusSeconds(20),
                            ),
                        )
                        assertThat(result).isEqualTo(ObservationAppendResult.APPENDED)
                        clockAppended.countDown()
                        assertThat(releaseClockCommit.await(5, TimeUnit.SECONDS)).isTrue()
                        result
                    },
                )
            }

            assertThat(clockAppended.await(5, TimeUnit.SECONDS)).isTrue()
            val staleTick = ObservationTestFixtures.tick(scope).copy(
                providerOccurredAt = ObservationTestFixtures.baseTime.plusSeconds(20),
                socketReceivedAt = ObservationTestFixtures.baseTime.plusSeconds(21),
                normalizedAt = ObservationTestFixtures.baseTime.plusSeconds(21),
            )
            val tickAppend = executor.submit<ObservationAppendResult> {
                tickAttempted.countDown()
                store.appendTick(staleTick)
            }

            assertThat(tickAttempted.await(5, TimeUnit.SECONDS)).isTrue()
            assertThatThrownBy { tickAppend.get(250, TimeUnit.MILLISECONDS) }
                .isInstanceOf(TimeoutException::class.java)

            releaseClockCommit.countDown()
            assertThat(clockAppend.get(5, TimeUnit.SECONDS)).isEqualTo(ObservationAppendResult.APPENDED)
            assertThat(tickAppend.get(5, TimeUnit.SECONDS)).isEqualTo(ObservationAppendResult.REJECTED)
            assertThat(
                store.appendTick(
                    staleTick.copy(
                        localReceiveSequence = 2,
                        clockSampleSequence = 2,
                    ),
                ),
            ).isEqualTo(ObservationAppendResult.APPENDED)
        } finally {
            releaseClockCommit.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `REST append sees an unhealthy clock committed while it waited`() {
        val scope = ObservationScope(
            runId = UUID.fromString("54000000-0000-0000-0000-000000000005"),
            provider = "toss",
        )
        createRunningRun(scope)
        val executor = Executors.newFixedThreadPool(2)
        val clockAppended = CountDownLatch(1)
        val releaseClockCommit = CountDownLatch(1)
        val restAttempted = CountDownLatch(1)

        try {
            val clockAppend = executor.submit<ObservationAppendResult> {
                checkNotNull(
                    TransactionTemplate(transactionManager).execute {
                        val result = store.appendClockSample(
                            ObservationTestFixtures.clockSample(scope, sampleSequence = 2).copy(
                                sampledAt = ObservationTestFixtures.baseTime.plusSeconds(20),
                                synchronized = false,
                            ),
                        )
                        assertThat(result).isEqualTo(ObservationAppendResult.APPENDED)
                        clockAppended.countDown()
                        assertThat(releaseClockCommit.await(5, TimeUnit.SECONDS)).isTrue()
                        result
                    },
                )
            }

            assertThat(clockAppended.await(5, TimeUnit.SECONDS)).isTrue()
            val poll = ObservationTestFixtures.restPoll(scope).copy(
                requestStartedAt = ObservationTestFixtures.baseTime.plusSeconds(21),
                observedAt = ObservationTestFixtures.baseTime.plusSeconds(22),
                normalizedAt = ObservationTestFixtures.baseTime.plusSeconds(22),
            )
            val restAppend = executor.submit<ObservationAppendResult> {
                restAttempted.countDown()
                store.appendRestPoll(poll)
            }

            assertThat(restAttempted.await(5, TimeUnit.SECONDS)).isTrue()
            assertThatThrownBy { restAppend.get(250, TimeUnit.MILLISECONDS) }
                .isInstanceOf(TimeoutException::class.java)

            releaseClockCommit.countDown()
            assertThat(clockAppend.get(5, TimeUnit.SECONDS)).isEqualTo(ObservationAppendResult.APPENDED)
            assertThat(restAppend.get(5, TimeUnit.SECONDS)).isEqualTo(ObservationAppendResult.REJECTED)
            assertThat(store.evidenceSnapshot(scope)!!.restPollCount).isZero()
        } finally {
            releaseClockCommit.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `market observation service is transactional when registered as a Spring bean`() {
        val service = observationServiceProvider.getObject()

        assertThat(AopUtils.isAopProxy(service)).isTrue()
    }

    private fun createRunningRun(scope: ObservationScope) {
        store.createRun(ObservationTestFixtures.run(scope = scope))
        store.createExpectedTickers(ObservationTestFixtures.expectedTickers(scope))
        store.createSemantics(ObservationTestFixtures.semantics(scope))
        assertThat(store.appendClockSample(ObservationTestFixtures.clockSample(scope)))
            .isEqualTo(ObservationAppendResult.APPENDED)
        assertThat(
            store.activateRun(
                scope,
                expectedClockSampleSequence = 1,
                changedAt = ObservationTestFixtures.baseTime.plusSeconds(1),
            ),
        ).isTrue()
    }
}
