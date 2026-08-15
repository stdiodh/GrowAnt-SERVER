package com.growant.market.observation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class RestPollObservationTest {
    @Test
    fun `cursorless page zero represents a complete single page poll`() {
        val observation = observation()

        assertThat(observation.pollRunId).isEqualTo(observation.requestId)
        assertThat(observation.pageOrdinal).isZero()
        assertThat(observation.requestCursor).isNull()
        assertThat(observation.nextCursor).isNull()
        assertThat(observation.pollTerminal).isTrue()
    }

    @Test
    fun `page zero preserves an optional initial request cursor`() {
        val observation = observation(requestCursor = "2026-08-17T15:30:00+09:00")

        assertThat(observation.pageOrdinal).isZero()
        assertThat(observation.requestCursor).isEqualTo("2026-08-17T15:30:00+09:00")
    }

    @Test
    fun `continuation and range-covered terminal pages preserve raw cursors`() {
        val root = observation(
            nextCursor = "2026-08-17T09:00:00+09:00",
            pollTerminal = false,
        )
        val observation = observation(
            requestId = CONTINUATION_REQUEST_ID,
            pollRunId = ROOT_REQUEST_ID,
            pageOrdinal = 1,
            requestCursor = "2026-08-17T09:00:00+09:00",
            nextCursor = "2026-08-16T15:30:00+09:00",
            pollTerminal = true,
        )

        assertThat(root.pollTerminal).isFalse()
        assertThat(observation.requestCursor).isEqualTo("2026-08-17T09:00:00+09:00")
        assertThat(observation.nextCursor).isEqualTo("2026-08-16T15:30:00+09:00")
    }

    @Test
    fun `page identity requires a request-rooted page zero and cursor-backed continuations`() {
        assertThatThrownBy {
            observation(pollRunId = CONTINUATION_REQUEST_ID)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            observation(
                requestId = CONTINUATION_REQUEST_ID,
                pollRunId = ROOT_REQUEST_ID,
                pageOrdinal = 1,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            observation(pageOrdinal = -1)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `cursors must be bounded printable raw values and must advance`() {
        assertThatThrownBy {
            observation(requestCursor = "contains space")
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            observation(nextCursor = "x".repeat(121))
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            observation(
                requestCursor = "2026-08-17T09:00:00+09:00",
                nextCursor = "2026-08-17T09:00:00+09:00",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `non-terminal pages require a successful outcome and next cursor`() {
        assertThatThrownBy {
            observation(pollTerminal = false)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            observation(
                nextCursor = "2026-08-16T15:30:00+09:00",
                pollTerminal = false,
                outcome = RestPollOutcome.NETWORK_ERROR,
                normalizedAt = null,
                httpStatus = null,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `failed pages terminate without advertising another cursor`() {
        val failed = observation(
            outcome = RestPollOutcome.HTTP_ERROR,
            normalizedAt = null,
            httpStatus = 500,
        )
        assertThat(failed.pollTerminal).isTrue()

        assertThatThrownBy {
            failed.copy(nextCursor = "2026-08-16T15:30:00+09:00")
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            failed.copy(returnedCandleCount = 1, eligibleCandleCount = 1)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun observation(
        requestId: UUID = ROOT_REQUEST_ID,
        pollRunId: UUID = requestId,
        pageOrdinal: Int = 0,
        requestCursor: String? = null,
        nextCursor: String? = null,
        pollTerminal: Boolean = true,
        outcome: RestPollOutcome = RestPollOutcome.SUCCESS,
        normalizedAt: Instant? = OBSERVED_AT,
        httpStatus: Int? = 200,
    ) = RestPollObservation(
        scope = ObservationScope(RUN_ID, "toss"),
        ticker = "005930",
        requestId = requestId,
        pollRunId = pollRunId,
        pageOrdinal = pageOrdinal,
        requestCursor = requestCursor,
        nextCursor = nextCursor,
        pollTerminal = pollTerminal,
        observedAt = OBSERVED_AT,
        requestStartedAt = REQUEST_STARTED_AT,
        normalizedAt = normalizedAt,
        requestedFrom = Instant.parse("2026-08-17T00:00:00Z"),
        requestedTo = Instant.parse("2026-08-17T01:00:00Z"),
        outcome = outcome,
        httpStatus = httpStatus,
        returnedCandleCount = if (outcome == RestPollOutcome.SUCCESS) 1 else 0,
        eligibleCandleCount = if (outcome == RestPollOutcome.SUCCESS) 1 else 0,
    )

    private companion object {
        val RUN_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val ROOT_REQUEST_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000001")
        val CONTINUATION_REQUEST_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000002")
        val REQUEST_STARTED_AT: Instant = Instant.parse("2026-08-17T00:00:01Z")
        val OBSERVED_AT: Instant = Instant.parse("2026-08-17T00:00:02Z")
    }
}

class ObservationEvidenceSnapshotTest {
    @Test
    fun `logical REST poll count cannot exceed page count`() {
        assertThatThrownBy {
            ObservationEvidenceSnapshot(
                scope = ObservationScope(
                    UUID.fromString("10000000-0000-0000-0000-000000000001"),
                    "toss",
                ),
                state = ObservationRunState.INVALID,
                clockSampleCount = 0,
                restPollCount = 1,
                restPollRunCount = 2,
                tickCount = 0,
                candleCount = 0,
                faultEventCount = 0,
                firstObservedAt = null,
                lastObservedAt = null,
                rowChecksumSha256 = "a".repeat(64),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
