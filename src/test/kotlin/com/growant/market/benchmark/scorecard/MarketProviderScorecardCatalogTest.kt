package com.growant.market.benchmark.scorecard

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

class MarketProviderScorecardCatalogTest {
    private val loader = MarketProviderScorecardLoader()

    @Test
    fun `loads all four catalog roles for authoring but refuses scored use`() {
        val bundle = loader.loadForAuthoring(CATALOG_DIRECTORY)

        assertThat(bundle.manifest.state).isEqualTo(ScorecardState.DRAFT)
        assertThat(bundle.manifest.scorecardVersion).isEqualTo("1.0.0-draft.1")
        assertThat(bundle.manifest.tiePolicy.maxDifferencePointsInclusive).isEqualByComparingTo("3")
        assertThat(bundle.manifest.tiePolicy.resolution).isEqualTo(TieResolution.NO_FORCED_WINNER)
        assertThat(bundle.scorecards.keys).containsExactlyInAnyOrderElementsOf(ScorecardRole.entries)

        bundle.scorecards.values.forEach { scorecard ->
            assertThat(scorecard.state).isEqualTo(ScorecardState.DRAFT)
            assertThat(scorecard.scoredRunsAllowed).isFalse()
            assertThat(scorecard.providerRankingAllowed).isFalse()
            assertThat(scorecard.documentedBaselineState)
                .isEqualTo(DocumentedBaselineState.DOCUMENTED_NOT_EXECUTABLE)
            assertThat(scorecard.scoringMetricsState).isEqualTo(ScoringMetricsState.UNRESOLVED)
            assertThat(scorecard.metrics).isEmpty()
            assertThat(scorecard.unresolvedDecisionIds).isNotEmpty()
            assertThat(scorecard.documentedHardGates).isNotEmpty()
            scorecard.documentedHardGates.forEach { gate ->
                assertThat(gate.definitionState).isEqualTo(DefinitionState.UNRESOLVED)
                assertThat(gate.metricId).isNull()
                assertThat(gate.executable).isFalse()
            }
        }

        assertThatThrownBy { loader.loadFrozen(CATALOG_DIRECTORY) }
            .isInstanceOf(MarketProviderScorecardValidationException::class.java)
            .hasMessageContaining("manifest.state must be FROZEN for scored runs")
    }

    @Test
    fun `preserves the documented area weights for every ranked role`() {
        val scorecards = loader.loadForAuthoring(CATALOG_DIRECTORY).scorecards

        assertAreaWeights(
            scorecards.getValue(ScorecardRole.REALTIME_POC),
            "accuracy-recovery" to "40",
            "latency-connectivity" to "30",
            "backfill" to "20",
            "implementation-complexity" to "10",
        )
        assertAreaWeights(
            scorecards.getValue(ScorecardRole.CANDLE_REFERENCE),
            "accuracy" to "40",
            "publication-stability" to "30",
            "history-pagination" to "20",
            "implementation-complexity" to "10",
        )
        assertAreaWeights(
            scorecards.getValue(ScorecardRole.KOSPI_FEED),
            "coverage-sla" to "30",
            "accuracy-recovery" to "25",
            "latency" to "15",
            "rights-total-cost" to "20",
            "operational-complexity" to "10",
        )

        val load = scorecards.getValue(ScorecardRole.GROWANT_LOAD)
        assertThat(load.scoringMode).isEqualTo(ScoringMode.GATE_ONLY)
        assertThat(load.areas).isEmpty()
        assertThat(load.providerRankingAllowed).isFalse()
    }

    @Test
    fun `preserves every documented hard gate id by role`() {
        val scorecards = loader.loadForAuthoring(CATALOG_DIRECTORY).scorecards

        EXPECTED_HARD_GATES.forEach { (role, expectedIds) ->
            assertThat(scorecards.getValue(role).documentedHardGates.map(DocumentedHardGate::id))
                .describedAs(role.name)
                .containsExactlyElementsOf(expectedIds)
        }
    }

    @Test
    fun `keeps unresolved concept variable and load thresholds typed without inventing values`() {
        val scorecards = loader.loadForAuthoring(CATALOG_DIRECTORY).scorecards
        val kospiGates = scorecards.getValue(ScorecardRole.KOSPI_FEED)
            .documentedHardGates.associateBy(DocumentedHardGate::id)
        val loadGates = scorecards.getValue(ScorecardRole.GROWANT_LOAD)
            .documentedHardGates.associateBy(DocumentedHardGate::id)

        val rpo = kospiGates.getValue("kospi.rpo-zero")
        assertThat(rpo.kind).isEqualTo(HardGateKind.CONCEPT)
        assertThat(rpo.operator).isNull()
        assertThat(rpo.numericThreshold).isNull()
        assertThat(rpo.booleanThreshold).isNull()
        assertThat(rpo.thresholdVariable).isNull()
        assertThat(rpo.unit).isNull()

        val cost = kospiGates.getValue("kospi.monthly-total-cost-krw")
        assertThat(cost.kind).isEqualTo(HardGateKind.VARIABLE)
        assertThat(cost.operator).isEqualTo(GateOperator.LTE)
        assertThat(cost.thresholdVariable).isEqualTo("MONTHLY_MARKET_DATA_BUDGET_KRW")
        assertThat(cost.unit).isEqualTo("KRW/month")
        assertThat(cost.numericThreshold).isNull()

        assertNumericGate(loadGates, "growant.http-error-rate-percent", GateOperator.LT, "1", "percent")
        assertNumericGate(loadGates, "growant.response-check-rate-percent", GateOperator.GT, "99", "percent")
        assertNumericGate(loadGates, "growant.dropped-iteration-count", GateOperator.EQ, "0", "count")
        assertNumericGate(loadGates, "growant.api-duration-p95-ms", GateOperator.LT, "200", "ms")
    }

    @Test
    fun `manifest hashes the exact role file bytes and records content provenance`() {
        val bundle = loader.loadForAuthoring(CATALOG_DIRECTORY)

        assertThat(bundle.manifestSha256)
            .isEqualTo(sha256(Files.readAllBytes(CATALOG_DIRECTORY.resolve("manifest.json"))))
        bundle.manifest.entries.forEach { entry ->
            assertThat(entry.sha256)
                .describedAs(entry.fileName)
                .isEqualTo(sha256(Files.readAllBytes(CATALOG_DIRECTORY.resolve(entry.fileName))))
        }
        bundle.scorecards.values.forEach { scorecard ->
            val provenance = scorecard.provenance.single()
            assertThat(provenance.path).isEqualTo("docs/market-provider-benchmark.md")
            assertThat(provenance.contentSha256)
                .isEqualTo("221abca96e7ba31ed0ccbfcae00624e350fed5c34de150fbf01041e0e1f9baf6")
        }
    }

    private fun assertAreaWeights(scorecard: RoleScorecard, vararg expected: Pair<String, String>) {
        assertThat(scorecard.scoringMode).isEqualTo(ScoringMode.WEIGHTED_RANKING)
        assertThat(scorecard.areas.map { area -> area.id to area.weightPoints.stripTrailingZeros().toPlainString() })
            .containsExactlyElementsOf(expected.toList())
        assertThat(scorecard.areas.flatMap(WeightedArea::metricIds)).isEmpty()
    }

    private fun assertNumericGate(
        gates: Map<String, DocumentedHardGate>,
        id: String,
        operator: GateOperator,
        threshold: String,
        unit: String,
    ) {
        val gate = gates.getValue(id)
        assertThat(gate.kind).isEqualTo(HardGateKind.NUMERIC)
        assertThat(gate.operator).isEqualTo(operator)
        assertThat(gate.numericThreshold).isEqualByComparingTo(threshold)
        assertThat(gate.unit).isEqualTo(unit)
    }

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private companion object {
        val CATALOG_DIRECTORY: Path = Path.of("benchmark/market-provider-scorecards/v1")

        val EXPECTED_HARD_GATES = mapOf(
            ScorecardRole.REALTIME_POC to listOf(
                "common.rights-admission",
                "common.independent-reference-accuracy",
                "common.secret-protection",
                "common.timestamp-semantics",
                "common.ntp-absolute-offset-ms",
                "common.official-call-limit-compliance",
                "realtime.tick-age-upper-bound-p95-ms",
                "realtime.tick-age-upper-bound-p99-ms",
                "realtime.final-unrecovered-gap-count",
                "realtime.unexplained-ohlcv-mismatch-count",
                "realtime.reconnect-resubscribe-elapsed-ms",
                "realtime.backfill-10m-50bucket-elapsed-ms",
                "realtime.soak-resource-leak-detected",
            ),
            ScorecardRole.CANDLE_REFERENCE to listOf(
                "common.rights-admission",
                "common.independent-reference-accuracy",
                "common.secret-protection",
                "common.timestamp-semantics",
                "common.ntp-absolute-offset-ms",
                "common.official-call-limit-compliance",
                "candle.scored-rest-total-rate-per-second",
                "candle.scored-ticker-count",
                "candle.scored-round-robin-order-fixed",
                "candle.first-observed-interval-upper-p95-ms",
                "candle.last-changed-elapsed-p95-ms",
                "candle.unexplained-ohlcv-mismatch-count",
                "candle.final-unrecovered-gap-count",
            ),
            ScorecardRole.KOSPI_FEED to listOf(
                "common.rights-admission",
                "common.independent-reference-accuracy",
                "common.secret-protection",
                "common.timestamp-semantics",
                "common.ntp-absolute-offset-ms",
                "kospi.contract-universe-rights-sla",
                "kospi.minimum-trading-day-count",
                "kospi.daily-contract-universe-coverage-percent",
                "kospi.final-unrecovered-gap-count",
                "kospi.final-stale-count",
                "kospi.rpo-zero",
                "kospi.outage-recovery-elapsed-ms",
                "kospi.contract-monthly-availability-percent",
                "kospi.monthly-total-cost-krw",
            ),
            ScorecardRole.GROWANT_LOAD to listOf(
                "growant.provider-endpoint-not-targeted",
                "growant.canonical-fixture-and-environment-match",
                "growant.plateau-protocol-conformance",
                "growant.http-error-rate-percent",
                "growant.response-check-rate-percent",
                "growant.dropped-iteration-count",
                "growant.api-duration-p95-ms",
            ),
        )
    }
}
