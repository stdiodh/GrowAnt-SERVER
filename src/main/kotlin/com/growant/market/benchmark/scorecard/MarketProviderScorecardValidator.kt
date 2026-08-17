package com.growant.market.benchmark.scorecard

import java.math.BigDecimal

class MarketProviderScorecardValidator {
    fun requireValidForAuthoring(bundle: LoadedScorecardBundle) {
        requireValid(bundle, requireFrozen = false)
    }

    fun requireValidFrozen(bundle: LoadedScorecardBundle) {
        requireValid(bundle, requireFrozen = true)
    }

    internal fun requireValidManifest(manifest: ScorecardManifest, requireFrozen: Boolean) {
        val violations = mutableListOf<String>()
        validateManifest(manifest, requireFrozen, violations)
        throwIfInvalid(violations)
    }

    private fun requireValid(bundle: LoadedScorecardBundle, requireFrozen: Boolean) {
        val violations = mutableListOf<String>()
        validateManifest(bundle.manifest, requireFrozen, violations)

        if (!SHA256_PATTERN.matches(bundle.manifestSha256)) {
            violations += "manifestSha256 must be a lowercase SHA-256"
        }

        val expectedRoles = ScorecardRole.entries.toSet()
        val actualRoles = bundle.scorecards.keys
        if (actualRoles != expectedRoles) {
            violations += "scorecards must contain exactly ${expectedRoles.sortedBy(Enum<*>::name)}"
        }

        val duplicateIds = bundle.scorecards.values
            .groupingBy(RoleScorecard::id)
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (duplicateIds.isNotEmpty()) {
            violations += "role scorecard ids must be unique: ${duplicateIds.sorted()}"
        }

        bundle.scorecards.forEach { (mapRole, scorecard) ->
            validateRoleScorecard(bundle.manifest, mapRole, scorecard, requireFrozen, violations)
        }

        throwIfInvalid(violations)
    }

    private fun validateManifest(
        manifest: ScorecardManifest,
        requireFrozen: Boolean,
        violations: MutableList<String>,
    ) {
        if (manifest.schemaVersion != MARKET_PROVIDER_SCORECARD_SCHEMA_VERSION) {
            violations += "manifest.schemaVersion must be $MARKET_PROVIDER_SCORECARD_SCHEMA_VERSION"
        }
        if (
            manifest.schemaVersion == MARKET_PROVIDER_SCORECARD_SCHEMA_VERSION &&
            manifest.state == ScorecardState.FROZEN
        ) {
            violations += "schema version 1 is DRAFT-only; FROZEN execution is not implemented"
        }
        if (!VERSION_PATTERN.matches(manifest.scorecardVersion)) {
            violations += "manifest.scorecardVersion must use the supported semantic version format"
        }
        if (requireFrozen && manifest.state != ScorecardState.FROZEN) {
            violations += "manifest.state must be FROZEN for scored runs"
        }
        if (manifest.state == ScorecardState.FROZEN && '-' in manifest.scorecardVersion) {
            violations += "a FROZEN manifest may not use a prerelease scorecardVersion"
        }
        if (manifest.tiePolicy.maxDifferencePointsInclusive.compareTo(TIE_MAX_POINTS) != 0) {
            violations += "manifest.tiePolicy.maxDifferencePointsInclusive must be exactly 3"
        }
        if (manifest.tiePolicy.resolution != TieResolution.NO_FORCED_WINNER) {
            violations += "manifest.tiePolicy.resolution must be NO_FORCED_WINNER"
        }

        val expectedRoles = ScorecardRole.entries.toSet()
        val manifestRoles = manifest.entries.map(ScorecardFileEntry::role)
        if (manifestRoles.toSet() != expectedRoles || manifestRoles.size != expectedRoles.size) {
            violations += "manifest.entries must contain each scorecard role exactly once"
        }
        duplicateValues(manifest.entries.map(ScorecardFileEntry::fileName)).takeIf(Set<String>::isNotEmpty)?.let {
            violations += "manifest entry fileName values must be unique: ${it.sorted()}"
        }
        manifest.entries.forEachIndexed { index, entry ->
            val field = "manifest.entries[$index]"
            if (!isSafeFileName(entry.fileName) || !entry.fileName.endsWith(".json")) {
                violations += "$field.fileName must be a relative JSON basename"
            }
            if (!SHA256_PATTERN.matches(entry.sha256)) {
                violations += "$field.sha256 must be a lowercase SHA-256"
            }
        }
    }

    private fun validateRoleScorecard(
        manifest: ScorecardManifest,
        mapRole: ScorecardRole,
        scorecard: RoleScorecard,
        requireFrozen: Boolean,
        violations: MutableList<String>,
    ) {
        val field = "scorecards.${mapRole.name}"
        if (scorecard.role != mapRole) {
            violations += "$field.role must match its map key"
        }
        if (scorecard.schemaVersion != manifest.schemaVersion) {
            violations += "$field.schemaVersion must match the manifest"
        }
        if (scorecard.scorecardVersion != manifest.scorecardVersion) {
            violations += "$field.scorecardVersion must match the manifest"
        }
        if (!IDENTIFIER_PATTERN.matches(scorecard.id)) {
            violations += "$field.id must be a lowercase identifier"
        }
        if (scorecard.state != manifest.state) {
            violations += "$field.state must match the manifest"
        }
        if (requireFrozen && scorecard.state != ScorecardState.FROZEN) {
            violations += "$field.state must be FROZEN for scored runs"
        }

        val expectedMode = if (scorecard.role == ScorecardRole.GROWANT_LOAD) {
            ScoringMode.GATE_ONLY
        } else {
            ScoringMode.WEIGHTED_RANKING
        }
        if (scorecard.scoringMode != expectedMode) {
            violations += "$field.scoringMode must be $expectedMode"
        }
        if (scorecard.role == ScorecardRole.GROWANT_LOAD && scorecard.providerRankingAllowed) {
            violations += "$field may never allow provider ranking"
        }

        when (scorecard.state) {
            ScorecardState.DRAFT -> validateDraftState(scorecard, field, violations)
            ScorecardState.FROZEN -> validateFrozenState(scorecard, field, violations)
        }

        validateAreas(scorecard, field, violations)
        validateHardGates(scorecard, field, violations)
        validateMetrics(scorecard, field, violations)
        validateReferences(scorecard, field, violations)
        validateUnresolvedDecisions(scorecard, field, violations)
        validateProvenance(scorecard, field, violations)
    }

    private fun validateDraftState(
        scorecard: RoleScorecard,
        field: String,
        violations: MutableList<String>,
    ) {
        if (scorecard.scoredRunsAllowed) {
            violations += "$field.scoredRunsAllowed must be false while DRAFT"
        }
        if (scorecard.providerRankingAllowed) {
            violations += "$field.providerRankingAllowed must be false while DRAFT"
        }
        if (scorecard.documentedBaselineState != DocumentedBaselineState.DOCUMENTED_NOT_EXECUTABLE) {
            violations += "$field.documentedBaselineState must be DOCUMENTED_NOT_EXECUTABLE while DRAFT"
        }
        if (scorecard.scoringMetricsState != ScoringMetricsState.UNRESOLVED) {
            violations += "$field.scoringMetricsState must be UNRESOLVED while DRAFT"
        }
        if (scorecard.unresolvedDecisionIds.isEmpty()) {
            violations += "$field.unresolvedDecisionIds must not be empty while DRAFT"
        }
    }

    private fun validateFrozenState(
        scorecard: RoleScorecard,
        field: String,
        violations: MutableList<String>,
    ) {
        if (!scorecard.scoredRunsAllowed) {
            violations += "$field.scoredRunsAllowed must be true while FROZEN"
        }
        val expectedRanking = scorecard.role != ScorecardRole.GROWANT_LOAD
        if (scorecard.providerRankingAllowed != expectedRanking) {
            violations += "$field.providerRankingAllowed must be $expectedRanking while FROZEN"
        }
        if (scorecard.documentedBaselineState != DocumentedBaselineState.EXECUTABLE) {
            violations += "$field.documentedBaselineState must be EXECUTABLE while FROZEN"
        }
        if (scorecard.scoringMetricsState != ScoringMetricsState.FROZEN) {
            violations += "$field.scoringMetricsState must be FROZEN while FROZEN"
        }
        if (scorecard.unresolvedDecisionIds.isNotEmpty()) {
            violations += "$field.unresolvedDecisionIds must be empty while FROZEN"
        }
        if (scorecard.metrics.isEmpty()) {
            violations += "$field.metrics must not be empty while FROZEN"
        }
    }

    private fun validateAreas(
        scorecard: RoleScorecard,
        field: String,
        violations: MutableList<String>,
    ) {
        val expectedAreaWeights = EXPECTED_AREA_WEIGHTS.getValue(scorecard.role)
        val actualAreaIds = scorecard.areas.map(WeightedArea::id).toSet()
        if (actualAreaIds != expectedAreaWeights.keys) {
            violations += "$field.areas must use the documented schema-v1 area ids ${expectedAreaWeights.keys}"
        }
        scorecard.areas.forEach { area ->
            expectedAreaWeights[area.id]?.let { expectedWeight ->
                if (area.weightPoints.compareTo(expectedWeight) != 0) {
                    violations += "$field.areas.${area.id}.weightPoints must be exactly $expectedWeight"
                }
            }
        }

        if (scorecard.scoringMode == ScoringMode.GATE_ONLY) {
            if (scorecard.areas.isNotEmpty()) {
                violations += "$field.areas must be empty in GATE_ONLY mode"
            }
            return
        }

        if (scorecard.areas.isEmpty()) {
            violations += "$field.areas must not be empty in WEIGHTED_RANKING mode"
            return
        }
        val duplicateAreaIds = duplicateValues(scorecard.areas.map(WeightedArea::id))
        if (duplicateAreaIds.isNotEmpty()) {
            violations += "$field.areas ids must be unique: ${duplicateAreaIds.sorted()}"
        }
        val duplicateMetricIds = duplicateValues(scorecard.areas.flatMap(WeightedArea::metricIds))
        if (duplicateMetricIds.isNotEmpty()) {
            violations += "$field area metricIds may appear in only one area: ${duplicateMetricIds.sorted()}"
        }

        scorecard.areas.forEachIndexed { index, area ->
            val areaField = "$field.areas[$index]"
            if (!IDENTIFIER_PATTERN.matches(area.id)) {
                violations += "$areaField.id must be a lowercase identifier"
            }
            if (area.weightPoints <= BigDecimal.ZERO) {
                violations += "$areaField.weightPoints must be positive"
            }
            if (scorecard.state == ScorecardState.FROZEN && area.metricIds.isEmpty()) {
                violations += "$areaField.metricIds must not be empty while FROZEN"
            }
            area.metricIds.forEach { metricId ->
                if (!IDENTIFIER_PATTERN.matches(metricId)) {
                    violations += "$areaField.metricIds contains an invalid identifier: $metricId"
                }
            }
        }
        val totalWeight = scorecard.areas.fold(BigDecimal.ZERO) { total, area -> total + area.weightPoints }
        if (totalWeight.compareTo(TOTAL_WEIGHT_POINTS) != 0) {
            violations += "$field.areas weightPoints must total exactly 100"
        }
    }

    private fun validateHardGates(
        scorecard: RoleScorecard,
        field: String,
        violations: MutableList<String>,
    ) {
        if (scorecard.documentedHardGates.isEmpty()) {
            violations += "$field.documentedHardGates must not be empty"
        }
        val duplicateGateIds = duplicateValues(scorecard.documentedHardGates.map(DocumentedHardGate::id))
        if (duplicateGateIds.isNotEmpty()) {
            violations += "$field.documentedHardGates ids must be unique: ${duplicateGateIds.sorted()}"
        }

        scorecard.documentedHardGates.forEachIndexed { index, gate ->
            val gateField = "$field.documentedHardGates[$index]"
            if (!IDENTIFIER_PATTERN.matches(gate.id)) {
                violations += "$gateField.id must be a lowercase identifier"
            }
            validateGateThreshold(gate, gateField, violations)

            if (gate.definitionState == DefinitionState.UNRESOLVED && gate.executable) {
                violations += "$gateField cannot be executable while its definition is UNRESOLVED"
            }
            if (gate.executable && gate.metricId.isNullOrBlank()) {
                violations += "$gateField.metricId is required when executable"
            }
            if (!gate.executable && gate.metricId != null) {
                violations += "$gateField.metricId must be null while non-executable"
            }
            if (scorecard.state == ScorecardState.FROZEN) {
                if (gate.definitionState != DefinitionState.RESOLVED || !gate.executable) {
                    violations += "$gateField must be RESOLVED and executable while FROZEN"
                }
                if (gate.kind == HardGateKind.CONCEPT || gate.kind == HardGateKind.VARIABLE) {
                    violations += "$gateField may not remain ${gate.kind} while FROZEN"
                }
            }
        }
    }

    private fun validateGateThreshold(
        gate: DocumentedHardGate,
        field: String,
        violations: MutableList<String>,
    ) {
        when (gate.kind) {
            HardGateKind.NUMERIC -> {
                if (gate.operator == null || gate.numericThreshold == null || gate.unit.isNullOrBlank()) {
                    violations += "$field NUMERIC gate requires operator, numericThreshold, and unit"
                }
                if (gate.booleanThreshold != null || gate.thresholdVariable != null) {
                    violations += "$field NUMERIC gate may only use numericThreshold"
                }
            }

            HardGateKind.BOOLEAN -> {
                if (gate.operator != GateOperator.EQ || gate.booleanThreshold == null) {
                    violations += "$field BOOLEAN gate requires EQ and booleanThreshold"
                }
                if (gate.numericThreshold != null || gate.thresholdVariable != null || gate.unit != null) {
                    violations += "$field BOOLEAN gate may only use booleanThreshold"
                }
            }

            HardGateKind.VARIABLE -> {
                if (
                    gate.operator == null ||
                    gate.thresholdVariable == null ||
                    !VARIABLE_PATTERN.matches(gate.thresholdVariable) ||
                    gate.unit.isNullOrBlank()
                ) {
                    violations += "$field VARIABLE gate requires operator, uppercase thresholdVariable, and unit"
                }
                if (gate.numericThreshold != null || gate.booleanThreshold != null) {
                    violations += "$field VARIABLE gate may only use thresholdVariable"
                }
            }

            HardGateKind.CONCEPT -> {
                if (
                    gate.operator != null ||
                    gate.numericThreshold != null ||
                    gate.booleanThreshold != null ||
                    gate.thresholdVariable != null ||
                    gate.unit != null
                ) {
                    violations += "$field CONCEPT gate may not define an executable threshold"
                }
            }
        }
    }

    private fun validateMetrics(
        scorecard: RoleScorecard,
        field: String,
        violations: MutableList<String>,
    ) {
        val duplicateMetricIds = duplicateValues(scorecard.metrics.map(MetricSpec::id))
        if (duplicateMetricIds.isNotEmpty()) {
            violations += "$field.metrics ids must be unique: ${duplicateMetricIds.sorted()}"
        }
        scorecard.metrics.forEachIndexed { index, metric ->
            val metricField = "$field.metrics[$index]"
            if (!IDENTIFIER_PATTERN.matches(metric.id)) {
                violations += "$metricField.id must be a lowercase identifier"
            }
            if (metric.unit.isBlank()) {
                violations += "$metricField.unit must not be blank"
            }
            when (metric.direction) {
                MetricDirection.LOWER_IS_BETTER -> if (metric.good >= metric.bad) {
                    violations += "$metricField requires good < bad"
                }

                MetricDirection.HIGHER_IS_BETTER -> if (metric.good <= metric.bad) {
                    violations += "$metricField requires good > bad"
                }

                MetricDirection.BINARY_PASS -> if (
                    metric.good.compareTo(BigDecimal.ONE) != 0 || metric.bad.compareTo(BigDecimal.ZERO) != 0
                ) {
                    violations += "$metricField BINARY_PASS requires good=1 and bad=0"
                }
            }
            if (metric.required && metric.notApplicablePolicy != NotApplicablePolicy.DISALLOW) {
                violations += "$metricField required metric must DISALLOW N/A"
            }
            if (!metric.required && metric.notApplicablePolicy == NotApplicablePolicy.DISALLOW) {
                violations += "$metricField optional metric must define an N/A outcome"
            }
            validateAggregation(metric, metricField, violations)
        }
    }

    private fun validateAggregation(
        metric: MetricSpec,
        field: String,
        violations: MutableList<String>,
    ) {
        val plan = metric.aggregation
        if (plan.dimensions.isEmpty()) {
            violations += "$field.aggregation.dimensions must not be empty"
        }
        if (plan.dimensions.distinct().size != plan.dimensions.size) {
            violations += "$field.aggregation.dimensions must be unique"
        }
        if (plan.steps.isEmpty()) {
            violations += "$field.aggregation.steps must not be empty"
            return
        }

        val remaining = plan.dimensions.toMutableSet()
        plan.steps.forEachIndexed { index, step ->
            val stepField = "$field.aggregation.steps[$index]"
            if (step.across.isEmpty()) {
                violations += "$stepField.across must not be empty"
            }
            if (step.across.distinct().size != step.across.size) {
                violations += "$stepField.across must be unique"
            }
            val unknown = step.across.toSet() - remaining
            if (unknown.isNotEmpty()) {
                violations += "$stepField.across contains absent or already consumed dimensions: $unknown"
            }
            remaining.removeAll(step.across.toSet())
            if (step.function == AggregationFunction.ALL && metric.direction != MetricDirection.BINARY_PASS) {
                violations += "$stepField ALL is only valid for BINARY_PASS metrics"
            }
        }
        if (remaining.isNotEmpty()) {
            violations += "$field.aggregation must consume every dimension; remaining=$remaining"
        }
    }

    private fun validateReferences(
        scorecard: RoleScorecard,
        field: String,
        violations: MutableList<String>,
    ) {
        val metricsById = scorecard.metrics.associateBy(MetricSpec::id)
        val areaMetricIds = scorecard.areas.flatMap(WeightedArea::metricIds).toSet()
        val gateMetricIds = scorecard.documentedHardGates.mapNotNull(DocumentedHardGate::metricId).toSet()

        (areaMetricIds - metricsById.keys).takeIf(Set<String>::isNotEmpty)?.let {
            violations += "$field.areas reference unknown metrics: ${it.sorted()}"
        }
        (gateMetricIds - metricsById.keys).takeIf(Set<String>::isNotEmpty)?.let {
            violations += "$field.documentedHardGates reference unknown metrics: ${it.sorted()}"
        }
        (metricsById.keys - areaMetricIds - gateMetricIds).takeIf(Set<String>::isNotEmpty)?.let {
            violations += "$field.metrics contains unused metrics: ${it.sorted()}"
        }

        scorecard.documentedHardGates.filter(DocumentedHardGate::executable).forEach { gate ->
            val metric = gate.metricId?.let(metricsById::get) ?: return@forEach
            val gateField = "$field.documentedHardGates.${gate.id}"
            if (!metric.required || metric.notApplicablePolicy != NotApplicablePolicy.DISALLOW) {
                violations += "$gateField must reference a required metric that disallows N/A"
            }
            if (gate.kind == HardGateKind.BOOLEAN && metric.direction != MetricDirection.BINARY_PASS) {
                violations += "$gateField BOOLEAN gate must reference a BINARY_PASS metric"
            }
            if (gate.kind != HardGateKind.BOOLEAN && gate.unit != metric.unit) {
                violations += "$gateField.unit must match its metric unit"
            }
            val allowedOperators = when (metric.direction) {
                MetricDirection.LOWER_IS_BETTER -> setOf(GateOperator.LT, GateOperator.LTE, GateOperator.EQ)
                MetricDirection.HIGHER_IS_BETTER -> setOf(GateOperator.GT, GateOperator.GTE, GateOperator.EQ)
                MetricDirection.BINARY_PASS -> setOf(GateOperator.EQ)
            }
            if (gate.operator !in allowedOperators) {
                violations += "$gateField.operator conflicts with metric direction ${metric.direction}"
            }
        }
    }

    private fun validateUnresolvedDecisions(
        scorecard: RoleScorecard,
        field: String,
        violations: MutableList<String>,
    ) {
        val duplicateIds = duplicateValues(scorecard.unresolvedDecisionIds)
        if (duplicateIds.isNotEmpty()) {
            violations += "$field.unresolvedDecisionIds must be unique: ${duplicateIds.sorted()}"
        }
        scorecard.unresolvedDecisionIds.forEach { id ->
            if (!IDENTIFIER_PATTERN.matches(id)) {
                violations += "$field.unresolvedDecisionIds contains an invalid identifier: $id"
            }
        }
    }

    private fun validateProvenance(
        scorecard: RoleScorecard,
        field: String,
        violations: MutableList<String>,
    ) {
        if (scorecard.provenance.isEmpty()) {
            violations += "$field.provenance must not be empty"
        }
        val duplicatePaths = duplicateValues(scorecard.provenance.map(ScorecardProvenance::path))
        if (duplicatePaths.isNotEmpty()) {
            violations += "$field.provenance paths must be unique: ${duplicatePaths.sorted()}"
        }
        scorecard.provenance.forEachIndexed { index, provenance ->
            val provenanceField = "$field.provenance[$index]"
            if (!isSafeRelativePath(provenance.path)) {
                violations += "$provenanceField.path must be a normalized relative POSIX path"
            }
            if (!SHA256_PATTERN.matches(provenance.contentSha256)) {
                violations += "$provenanceField.contentSha256 must be a lowercase SHA-256"
            }
        }
    }

    private fun throwIfInvalid(violations: List<String>) {
        if (violations.isEmpty()) return
        throw MarketProviderScorecardValidationException(
            message = violations.joinToString(
                separator = "\n- ",
                prefix = "Invalid market provider scorecard:\n- ",
            ),
            violations = violations.toList(),
        )
    }

    private fun duplicateValues(values: List<String>): Set<String> = values
        .groupingBy { it }
        .eachCount()
        .filterValues { it > 1 }
        .keys

    private fun isSafeFileName(value: String): Boolean =
        value.isNotBlank() &&
            value != "." &&
            value != ".." &&
            '/' !in value &&
            '\\' !in value

    private fun isSafeRelativePath(value: String): Boolean {
        if (value.isBlank() || value.startsWith('/') || '\\' in value) return false
        val segments = value.split('/')
        return segments.all { it.isNotBlank() && it != "." && it != ".." }
    }

    private companion object {
        val TIE_MAX_POINTS = BigDecimal("3")
        val TOTAL_WEIGHT_POINTS = BigDecimal("100")
        val VERSION_PATTERN = Regex(
            "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)" +
                "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?",
        )
        val IDENTIFIER_PATTERN = Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*")
        val VARIABLE_PATTERN = Regex("[A-Z][A-Z0-9_]*")
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        val EXPECTED_AREA_WEIGHTS = mapOf(
            ScorecardRole.REALTIME_POC to linkedMapOf(
                "accuracy-recovery" to BigDecimal("40"),
                "latency-connectivity" to BigDecimal("30"),
                "backfill" to BigDecimal("20"),
                "implementation-complexity" to BigDecimal("10"),
            ),
            ScorecardRole.CANDLE_REFERENCE to linkedMapOf(
                "accuracy" to BigDecimal("40"),
                "publication-stability" to BigDecimal("30"),
                "history-pagination" to BigDecimal("20"),
                "implementation-complexity" to BigDecimal("10"),
            ),
            ScorecardRole.KOSPI_FEED to linkedMapOf(
                "coverage-sla" to BigDecimal("30"),
                "accuracy-recovery" to BigDecimal("25"),
                "latency" to BigDecimal("15"),
                "rights-total-cost" to BigDecimal("20"),
                "operational-complexity" to BigDecimal("10"),
            ),
            ScorecardRole.GROWANT_LOAD to emptyMap(),
        )
    }
}
