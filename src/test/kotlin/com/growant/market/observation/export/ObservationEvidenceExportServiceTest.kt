package com.growant.market.observation.export

import com.growant.market.observation.ObservationEvidenceBundle
import com.growant.market.observation.ObservationEvidenceSnapshot
import com.growant.market.observation.ObservationRunState
import com.growant.market.observation.ObservationTestFixtures
import com.growant.market.observation.port.MarketObservationRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneOffset

class ObservationEvidenceExportServiceTest {
    @TempDir
    lateinit var directory: Path

    private val repository = mock(MarketObservationRepository::class.java)
    private val exportedAt = ObservationTestFixtures.baseTime.plusSeconds(600)
    private val completedAt = ObservationTestFixtures.baseTime.plusSeconds(300)
    private val service = ObservationEvidenceExportService(
        repository = repository,
        writer = ObservationEvidenceManifestWriter(),
        clock = Clock.fixed(exportedAt, ZoneOffset.UTC),
    )

    @Test
    fun `exports one repository snapshot with honest export time and immutable benchmark identity`() {
        val run = ObservationTestFixtures.run(
            state = ObservationRunState.COMPLETED,
            startedAt = ObservationTestFixtures.baseTime,
            completedAt = completedAt,
        )
        given(repository.evidenceBundle(run.scope)).willReturn(completedBundle(run))

        val output = service.export(run.scope, directory.resolve("manifest.json"))

        val json = Files.readString(output)
        assertThat(json)
            .contains("\"observationSchemaVersion\": 3")
            .contains("\"benchmarkSpecId\": \"provider-benchmark-v1\"")
            .contains("\"benchmarkSpecChecksumSha256\": \"${"c".repeat(64)}\"")
            .contains("\"sourceCommitSha\": \"${"d".repeat(40)}\"")
            .contains("\"sourceTreeDirty\": false")
            .contains("\"windowStart\": \"${run.windowStart}\"")
            .contains("\"windowEnd\": \"${run.windowEnd}\"")
            .contains("\"expectedTickerCount\": 1")
            .contains("\"tickerSetChecksumSha256\": \"${run.tickerSetChecksumSha256}\"")
            .contains("\"tickersInOrdinalOrder\": [\n    \"100001\"\n  ]")
            .contains("\"completedAt\": \"$completedAt\"")
            .contains("\"exportedAt\": \"$exportedAt\"")
            .contains("\"sampleSequence\": 1")
            .contains("\"semantics\": {")
            .contains("\"timestampOrigin\": \"PROVIDER_EVENT\"")
            .contains("\"rights\": {")
            .contains("\"storage\": \"ALLOWED\"")
            .contains("\"rightsSha256\": \"${"a".repeat(64)}\"")
            .contains("\"rowsSha256\": \"${"f".repeat(64)}\"")
            .doesNotContain("synthetic-fixture-rights-v1")
            .doesNotContain("50000")
            .doesNotContain("price")
            .doesNotContain("series")
            .doesNotContain("secret")
            .doesNotContain("token")
        verify(repository).evidenceBundle(run.scope)
        verifyNoMoreInteractions(repository)
    }

    @Test
    fun `allows an invalid run to export an explicitly incomplete frozen bundle`() {
        val run = ObservationTestFixtures.run(
            state = ObservationRunState.INVALID,
            completedAt = completedAt,
        )
        val bundle = ObservationEvidenceBundle(
            schemaVersion = 3,
            run = run,
            semantics = null,
            activationClockSample = null,
            expectedTickers = emptyList(),
            snapshot = snapshot(run.state),
        )
        given(repository.evidenceBundle(run.scope)).willReturn(bundle)

        val output = service.export(run.scope, directory.resolve("invalid.json"))

        assertThat(Files.readString(output))
            .contains("\"state\": \"INVALID\"")
            .contains("\"startedAt\": null")
            .contains("\"exportedAt\": \"$exportedAt\"")
            .contains("\"clock\": null")
            .contains("\"semanticsSha256\": null")
            .contains("\"tickersInOrdinalOrder\": [\n  ]")
    }

    @Test
    fun `rejects a running run from the bundle before creating an output file`() {
        val run = ObservationTestFixtures.run(
            state = ObservationRunState.RUNNING,
            startedAt = ObservationTestFixtures.baseTime,
        )
        val output = directory.resolve("running.json")
        given(repository.evidenceBundle(run.scope)).willReturn(
            ObservationEvidenceBundle(
                schemaVersion = 3,
                run = run,
                semantics = ObservationTestFixtures.semantics(run.scope),
                activationClockSample = ObservationTestFixtures.clockSample(run.scope),
                expectedTickers = ObservationTestFixtures.expectedTickers(run.scope),
                snapshot = snapshot(run.state),
            ),
        )

        assertThatThrownBy { service.export(run.scope, output) }
            .isInstanceOf(ObservationEvidenceUnavailableException::class.java)
            .hasMessage("Observation evidence unavailable: run is not terminal")
        assertThat(output).doesNotExist()
    }

    @Test
    fun `rejects a completed bundle whose ticker checksum no longer matches`() {
        val run = ObservationTestFixtures.run(
            state = ObservationRunState.COMPLETED,
            startedAt = ObservationTestFixtures.baseTime,
            completedAt = completedAt,
        ).copy(tickerSetChecksumSha256 = "e".repeat(64))
        val output = directory.resolve("mismatch.json")
        given(repository.evidenceBundle(run.scope)).willReturn(completedBundle(run))

        assertThatThrownBy { service.export(run.scope, output) }
            .isInstanceOf(ObservationEvidenceUnavailableException::class.java)
            .hasMessage("Observation evidence unavailable: expected ticker checksum mismatch")
        assertThat(output).doesNotExist()
    }

    private fun completedBundle(run: com.growant.market.observation.ObservationRun) = ObservationEvidenceBundle(
        schemaVersion = 3,
        run = run,
        semantics = ObservationTestFixtures.semantics(run.scope),
        activationClockSample = ObservationTestFixtures.clockSample(run.scope),
        expectedTickers = ObservationTestFixtures.expectedTickers(run.scope),
        snapshot = snapshot(run.state),
    )

    private fun snapshot(state: ObservationRunState) = ObservationEvidenceSnapshot(
        scope = ObservationTestFixtures.scope(),
        state = state,
        clockSampleCount = 1,
        restPollCount = 2,
        tickCount = 3,
        candleCount = 4,
        faultEventCount = 5,
        firstObservedAt = ObservationTestFixtures.baseTime,
        lastObservedAt = exportedAt,
        rowChecksumSha256 = "f".repeat(64),
    )
}
