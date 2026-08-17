package com.growant.market.benchmark.scorecard

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path

class MarketProviderScorecardValidatorTest {
    private val validator = MarketProviderScorecardValidator()

    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `schema version one rejects a seemingly complete frozen bundle`() {
        val bundle = ScorecardTestFixtures.seeminglyCompleteSchemaV1FrozenBundle()

        val exception = assertThrows<MarketProviderScorecardValidationException> {
            validator.requireValidFrozen(bundle)
        }
        org.assertj.core.api.Assertions.assertThat(exception.violations)
            .containsExactly(DRAFT_ONLY_VIOLATION)
    }

    @Test
    fun `loader rejects a seemingly complete schema version one frozen catalog`() {
        val directory = ScorecardTestFixtures.writeSeeminglyCompleteSchemaV1FrozenBundle(
            tempDirectory.resolve("frozen"),
        )

        assertThatThrownBy { MarketProviderScorecardLoader().loadFrozen(directory) }
            .isInstanceOf(MarketProviderScorecardValidationException::class.java)
            .hasMessageContaining(DRAFT_ONLY_VIOLATION)
    }

    @Test
    fun `rejects unresolved decisions from a frozen scorecard`() {
        val bundle = change(ScorecardRole.REALTIME_POC) { scorecard ->
            scorecard.copy(unresolvedDecisionIds = listOf("metric-catalog"))
        }

        assertViolation(bundle, "unresolvedDecisionIds must be empty while FROZEN")
    }

    @Test
    fun `rejects ranked area weights that do not total exactly one hundred`() {
        val bundle = change(ScorecardRole.CANDLE_REFERENCE) { scorecard ->
            scorecard.copy(
                areas = scorecard.areas.mapIndexed { index, area ->
                    if (index == 0) area.copy(weightPoints = BigDecimal("39.99")) else area
                },
            )
        }

        assertViolation(bundle, "weightPoints must total exactly 100")
    }

    @Test
    fun `rejects documented area weight drift even when the total remains one hundred`() {
        val bundle = change(ScorecardRole.CANDLE_REFERENCE) { scorecard ->
            scorecard.copy(
                areas = scorecard.areas.map { area ->
                    when (area.id) {
                        "accuracy" -> area.copy(weightPoints = BigDecimal("39"))
                        "publication-stability" -> area.copy(weightPoints = BigDecimal("31"))
                        else -> area
                    }
                },
            )
        }

        assertViolation(bundle, "areas.accuracy.weightPoints must be exactly 40")
        assertViolation(bundle, "areas.publication-stability.weightPoints must be exactly 30")
    }

    @Test
    fun `rejects documented area id drift`() {
        val bundle = change(ScorecardRole.REALTIME_POC) { scorecard ->
            scorecard.copy(
                areas = scorecard.areas.mapIndexed { index, area ->
                    if (index == 0) area.copy(id = "renamed-accuracy") else area
                },
            )
        }

        assertViolation(bundle, "areas must use the documented schema-v1 area ids")
    }

    @Test
    fun `rejects duplicate role scorecard ids`() {
        val original = ScorecardTestFixtures.seeminglyCompleteSchemaV1FrozenBundle()
        val duplicateId = original.scorecards.getValue(ScorecardRole.REALTIME_POC).id
        val bundle = ScorecardTestFixtures.replaceScorecard(original, ScorecardRole.CANDLE_REFERENCE) { scorecard ->
            scorecard.copy(id = duplicateId)
        }

        assertViolation(bundle, "role scorecard ids must be unique")
    }

    @Test
    fun `rejects duplicate metric ids`() {
        val bundle = change(ScorecardRole.REALTIME_POC) { scorecard ->
            scorecard.copy(metrics = scorecard.metrics + scorecard.metrics.first())
        }

        assertViolation(bundle, "metrics ids must be unique")
    }

    @Test
    fun `rejects an area reference to a missing metric`() {
        val bundle = change(ScorecardRole.CANDLE_REFERENCE) { scorecard ->
            scorecard.copy(
                areas = scorecard.areas.mapIndexed { index, area ->
                    if (index == 0) area.copy(metricIds = listOf("missing.metric")) else area
                },
            )
        }

        assertViolation(bundle, "areas reference unknown metrics: [missing.metric]")
    }

    @Test
    fun `rejects a hard gate reference to a missing metric`() {
        val bundle = change(ScorecardRole.KOSPI_FEED) { scorecard ->
            scorecard.copy(
                documentedHardGates = scorecard.documentedHardGates.map { gate ->
                    gate.copy(metricId = "missing.gate-metric")
                },
            )
        }

        assertViolation(bundle, "documentedHardGates reference unknown metrics: [missing.gate-metric]")
    }

    @Test
    fun `rejects missing metrics from a frozen scorecard`() {
        val bundle = change(ScorecardRole.REALTIME_POC) { scorecard ->
            scorecard.copy(
                areas = scorecard.areas.map { area -> area.copy(metricIds = emptyList()) },
                documentedHardGates = scorecard.documentedHardGates.map { gate ->
                    gate.copy(metricId = "missing.metric")
                },
                metrics = emptyList(),
            )
        }

        assertViolation(bundle, "metrics must not be empty while FROZEN")
        assertViolation(bundle, "metricIds must not be empty while FROZEN")
    }

    @Test
    fun `rejects a bundle with a missing role`() {
        val original = ScorecardTestFixtures.seeminglyCompleteSchemaV1FrozenBundle()
        val bundle = original.copy(
            scorecards = original.scorecards - ScorecardRole.KOSPI_FEED,
        )

        assertViolation(bundle, "scorecards must contain exactly")
    }

    @Test
    fun `growant load can never allow provider ranking`() {
        val bundle = change(ScorecardRole.GROWANT_LOAD) { scorecard ->
            scorecard.copy(providerRankingAllowed = true)
        }

        assertViolation(bundle, "may never allow provider ranking")
    }

    @Test
    fun `growant load remains gate-only`() {
        val bundle = change(ScorecardRole.GROWANT_LOAD) { scorecard ->
            scorecard.copy(scoringMode = ScoringMode.WEIGHTED_RANKING)
        }

        assertViolation(bundle, "scoringMode must be GATE_ONLY")
    }

    @Test
    fun `rejects a frozen metric whose good and bad contradict its direction`() {
        val bundle = change(ScorecardRole.REALTIME_POC) { scorecard ->
            val invalid = scorecard.metrics.first().copy(
                good = BigDecimal("100"),
                bad = BigDecimal("10"),
            )
            scorecard.copy(metrics = listOf(invalid) + scorecard.metrics.drop(1))
        }

        assertViolation(bundle, "requires good < bad")
    }

    @Test
    fun `rejects a required metric that permits not applicable`() {
        val bundle = change(ScorecardRole.REALTIME_POC) { scorecard ->
            val invalid = scorecard.metrics.first().copy(
                notApplicablePolicy = NotApplicablePolicy.SCORE_ZERO,
            )
            scorecard.copy(metrics = listOf(invalid) + scorecard.metrics.drop(1))
        }

        assertViolation(bundle, "required metric must DISALLOW N/A")
    }

    @Test
    fun `rejects a hard gate comparator that contradicts metric direction`() {
        val bundle = change(ScorecardRole.REALTIME_POC) { scorecard ->
            scorecard.copy(
                documentedHardGates = scorecard.documentedHardGates.map { gate ->
                    gate.copy(operator = GateOperator.GTE)
                },
            )
        }

        assertViolation(bundle, "operator conflicts with metric direction LOWER_IS_BETTER")
    }

    @Test
    fun `rejects a concept hard gate from a frozen scorecard`() {
        val bundle = change(ScorecardRole.REALTIME_POC) { scorecard ->
            scorecard.copy(
                documentedHardGates = scorecard.documentedHardGates.map { gate ->
                    gate.copy(
                        kind = HardGateKind.CONCEPT,
                        operator = null,
                        numericThreshold = null,
                        unit = null,
                    )
                },
            )
        }

        assertViolation(bundle, "may not remain CONCEPT while FROZEN")
    }

    @Test
    fun `rejects a variable hard gate from a frozen scorecard`() {
        val bundle = change(ScorecardRole.KOSPI_FEED) { scorecard ->
            scorecard.copy(
                documentedHardGates = scorecard.documentedHardGates.map { gate ->
                    gate.copy(
                        kind = HardGateKind.VARIABLE,
                        numericThreshold = null,
                        thresholdVariable = "TEST_THRESHOLD",
                    )
                },
            )
        }

        assertViolation(bundle, "may not remain VARIABLE while FROZEN")
    }

    private fun change(
        role: ScorecardRole,
        transform: (RoleScorecard) -> RoleScorecard,
    ): LoadedScorecardBundle = ScorecardTestFixtures.replaceScorecard(
        ScorecardTestFixtures.seeminglyCompleteSchemaV1FrozenBundle(),
        role,
        transform,
    )

    private fun assertViolation(bundle: LoadedScorecardBundle, expected: String) {
        val exception = assertThrows<MarketProviderScorecardValidationException> {
            validator.requireValidFrozen(bundle)
        }
        org.assertj.core.api.Assertions.assertThat(exception.violations)
            .contains(DRAFT_ONLY_VIOLATION)
        org.assertj.core.api.Assertions.assertThat(
            exception.violations.any { violation -> violation.contains(expected) },
        ).isTrue()
    }

    private companion object {
        const val DRAFT_ONLY_VIOLATION =
            "schema version 1 is DRAFT-only; FROZEN execution is not implemented"
    }
}
