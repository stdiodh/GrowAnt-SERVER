package com.growant.market.observation.export

import com.growant.market.observation.ObservationTickerSetChecksum
import com.growant.market.observation.ObservationTestFixtures
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class ObservationEvidenceManifestWriterTest {
    @TempDir
    lateinit var directory: Path

    private val writer = ObservationEvidenceManifestWriter()

    @Test
    fun `writes the same frozen bundle as identical canonical bytes`() {
        val manifest = manifest()
        val first = writer.write(directory.resolve("first.json"), manifest)
        val second = writer.write(
            directory.resolve("second.json"),
            manifest.copy(recordCounts = linkedMapOf("tick" to 10, "candle" to 2)),
        )

        assertThat(Files.readAllBytes(first)).isEqualTo(Files.readAllBytes(second))

        val json = Files.readString(first)
        assertThat(json)
            .contains("\"manifestFormatVersion\": \"1\"")
            .contains("\"observationSchemaVersion\": 4")
            .contains("\"completedAt\": \"2026-08-15T00:10:00Z\"")
            .contains("\"exportedAt\": \"2026-08-15T00:11:00Z\"")
            .contains("\"benchmarkSpecId\": \"provider-benchmark-v1\"")
            .contains("\"sourceTreeDirty\": false")
            .contains("\"sampleSequence\": 7")
        assertThat(json.indexOf("\"candle\"")).isLessThan(json.indexOf("\"tick\""))
        assertThat(json)
            .doesNotContain("price")
            .doesNotContain("series")
            .doesNotContain("secret")
            .doesNotContain("token")
            .doesNotContain("Authorization")
            .doesNotContain("Bearer")
            .doesNotContain("appKey")
            .doesNotContain("apiKey")
            .doesNotContain("credential")
            .doesNotContain("rawPayload")
            .doesNotContain(SENSITIVE_SENTINEL)
    }

    @Test
    fun `does not overwrite an existing evidence file`() {
        val output = directory.resolve("manifest.json")
        Files.writeString(output, "existing")

        assertThatThrownBy { writer.write(output, manifest()) }
            .isInstanceOf(FileAlreadyExistsException::class.java)
        assertThat(Files.readString(output)).isEqualTo("existing")
    }

    @Test
    fun `rejects a nonterminal run`() {
        assertThatThrownBy { manifest(state = "RUNNING") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("completed or invalid")
    }

    @Test
    fun `rejects a completed manifest with a changed ticker checksum`() {
        assertThatThrownBy { manifest().copy(tickerSetChecksumSha256 = "9".repeat(64)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("complete evidence metadata")
    }

    private fun manifest(state: String = "COMPLETED"): ObservationEvidenceManifest {
        val scope = ObservationTestFixtures.scope(
            runId = UUID.fromString("0ccddba6-a97e-4ef7-9250-babaf2ab7643"),
            provider = "kis",
        )
        return ObservationEvidenceManifest(
            observationSchemaVersion = 4,
            runId = scope.runId,
            provider = scope.provider,
            role = "REALTIME_POC",
            origin = "SYNTHETIC",
            state = state,
            benchmarkSpecId = "provider-benchmark-v1",
            benchmarkSpecChecksumSha256 = "0".repeat(64),
            sourceCommitSha = "a".repeat(40),
            sourceTreeDirty = false,
            windowStart = Instant.parse("2026-08-15T00:00:00Z"),
            windowEnd = Instant.parse("2026-08-15T00:10:00Z"),
            expectedTickerCount = 1,
            tickerSetChecksumSha256 = ObservationTickerSetChecksum.sha256(listOf("005930")),
            tickersInOrdinalOrder = listOf("005930"),
            startedAt = Instant.parse("2026-08-15T00:00:00Z"),
            completedAt = Instant.parse("2026-08-15T00:10:00Z"),
            exportedAt = Instant.parse("2026-08-15T00:11:00Z"),
            retentionUntil = Instant.parse("2026-08-22T00:10:00Z"),
            activationClockSampleSequence = 7,
            clockSampledAt = Instant.parse("2026-08-15T00:09:59Z"),
            localClockOffsetMicros = -20_000,
            clockUncertaintyMicros = 10_000,
            clockSynchronized = true,
            clockSource = "NTP",
            semantics = ObservationTestFixtures.semantics(scope).copy(
                documentEvidenceId = SENSITIVE_SENTINEL,
            ),
            rights = ObservationTestFixtures.rights().copy(
                evidenceId = SENSITIVE_SENTINEL,
            ),
            rowChecksumSha256 = "3".repeat(64),
            recordCounts = mapOf(
                "candle" to 2,
                "tick" to 10,
            ),
        )
    }

    private companion object {
        const val SENSITIVE_SENTINEL = "sk_test_observation_secret_123456"
    }
}
