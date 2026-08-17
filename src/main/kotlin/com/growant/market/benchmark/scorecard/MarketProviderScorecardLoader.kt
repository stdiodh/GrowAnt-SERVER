package com.growant.market.benchmark.scorecard

import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.cfg.CoercionAction
import tools.jackson.databind.cfg.CoercionInputShape
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.type.LogicalType
import tools.jackson.module.kotlin.KotlinFeature
import tools.jackson.module.kotlin.KotlinModule
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

class MarketProviderScorecardLoader(
    private val validator: MarketProviderScorecardValidator = MarketProviderScorecardValidator(),
) {
    fun loadForAuthoring(
        directory: Path,
        manifestFileName: String = DEFAULT_MANIFEST_FILE_NAME,
    ): LoadedScorecardBundle = load(directory, manifestFileName, requireFrozen = false)

    fun loadFrozen(
        directory: Path,
        manifestFileName: String = DEFAULT_MANIFEST_FILE_NAME,
    ): LoadedScorecardBundle = load(directory, manifestFileName, requireFrozen = true)

    private fun load(
        directory: Path,
        manifestFileName: String,
        requireFrozen: Boolean,
    ): LoadedScorecardBundle {
        val root = validateDirectory(directory)
        requireSafeFileName(manifestFileName, "manifestFileName")

        val manifestBytes = readJsonBytes(root.resolve(manifestFileName), "manifest")
        val manifestSha256 = sha256(manifestBytes)
        val manifest = parse(manifestBytes, ScorecardManifest::class.java, "manifest")
        validator.requireValidManifest(manifest, requireFrozen)

        val scorecards = linkedMapOf<ScorecardRole, RoleScorecard>()
        manifest.entries.forEach { entry ->
            val scorecardPath = root.resolve(entry.fileName).normalize()
            if (scorecardPath.parent != root) {
                fail("scorecard file escapes its directory: ${entry.fileName}")
            }

            val scorecardBytes = readRawBytes(scorecardPath, "scorecard ${entry.role}")
            val actualSha256 = sha256(scorecardBytes)
            if (actualSha256 != entry.sha256) {
                fail("scorecard ${entry.role} SHA-256 does not match the manifest")
            }
            validateJsonEncoding(scorecardBytes, "scorecard ${entry.role}")
            requireExplicitNullableHardGateFields(scorecardBytes, "scorecard ${entry.role}")
            val scorecard = parse(scorecardBytes, RoleScorecard::class.java, "scorecard ${entry.role}")
            scorecards[entry.role] = scorecard
        }

        return LoadedScorecardBundle(
            manifest = manifest,
            scorecards = scorecards.toMap(),
            manifestSha256 = manifestSha256,
        ).also { bundle ->
            if (requireFrozen) {
                validator.requireValidFrozen(bundle)
            } else {
                validator.requireValidForAuthoring(bundle)
            }
        }
    }

    private fun validateDirectory(directory: Path): Path {
        val normalized = directory.toAbsolutePath().normalize()
        if (Files.isSymbolicLink(normalized)) {
            fail("scorecard directory may not be a symbolic link")
        }
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            fail("scorecard directory does not exist or is not a directory")
        }
        return normalized
    }

    private fun readJsonBytes(path: Path, label: String): ByteArray =
        readRawBytes(path, label).also { validateJsonEncoding(it, label) }

    private fun readRawBytes(path: Path, label: String): ByteArray {
        if (Files.isSymbolicLink(path)) {
            fail("$label may not be a symbolic link")
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            fail("$label does not exist or is not a regular file")
        }
        val size = Files.size(path)
        if (size > MAX_JSON_BYTES) {
            fail("$label exceeds the $MAX_JSON_BYTES byte limit")
        }
        return try {
            Files.readAllBytes(path)
        } catch (exception: Exception) {
            throw MarketProviderScorecardValidationException(
                message = "Unable to read $label",
                cause = exception,
            )
        }
    }

    private fun validateJsonEncoding(bytes: ByteArray, label: String) {
        if (SUPPORTED_BOMS.any { prefix -> bytes.startsWith(prefix) }) {
            fail("$label must be UTF-8 without a byte-order mark")
        }
        if (bytes.any { it == CARRIAGE_RETURN }) {
            fail("$label must use LF line endings")
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
        } catch (exception: Exception) {
            throw MarketProviderScorecardValidationException(
                message = "$label must contain valid UTF-8",
                cause = exception,
            )
        }
    }

    private fun <T> parse(bytes: ByteArray, type: Class<T>, label: String): T = try {
        OBJECT_MAPPER.readValue(bytes, type)
    } catch (exception: Exception) {
        throw MarketProviderScorecardValidationException(
            message = "$label is not valid strict scorecard JSON: ${exception.message}",
            cause = exception,
        )
    }

    private fun requireExplicitNullableHardGateFields(bytes: ByteArray, label: String) {
        val root: JsonNode = try {
            OBJECT_MAPPER.readTree(bytes)
        } catch (exception: Exception) {
            throw MarketProviderScorecardValidationException(
                message = "$label is not valid strict scorecard JSON: ${exception.message}",
                cause = exception,
            )
        }
        val hardGates = root.get("documentedHardGates") ?: return
        if (!hardGates.isArray) return

        hardGates.forEachIndexed { index, hardGate ->
            if (!hardGate.isObject) return@forEachIndexed
            val missingFields = NULLABLE_HARD_GATE_FIELDS.filter { field -> hardGate.get(field) == null }
            if (missingFields.isNotEmpty()) {
                fail(
                    "$label documentedHardGates[$index] must explicitly contain nullable fields: " +
                        missingFields.joinToString(),
                )
            }
        }
    }

    private fun requireSafeFileName(fileName: String, field: String) {
        if (
            fileName.isBlank() ||
            fileName == "." ||
            fileName == ".." ||
            '/' in fileName ||
            '\\' in fileName ||
            Path.of(fileName).isAbsolute
        ) {
            fail("$field must be a relative basename")
        }
    }

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun fail(message: String): Nothing = throw MarketProviderScorecardValidationException(message)

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

    private companion object {
        const val DEFAULT_MANIFEST_FILE_NAME = "manifest.json"
        const val MAX_JSON_BYTES = 1_048_576L
        val NULLABLE_HARD_GATE_FIELDS = listOf(
            "metricId",
            "operator",
            "numericThreshold",
            "booleanThreshold",
            "thresholdVariable",
            "unit",
        )
        val CARRIAGE_RETURN: Byte = '\r'.code.toByte()
        val SUPPORTED_BOMS = listOf(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()),
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()),
            byteArrayOf(0x00, 0x00, 0xFE.toByte(), 0xFF.toByte()),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00),
        )
        val OBJECT_MAPPER: ObjectMapper = JsonMapper.builder()
            .addModule(
                KotlinModule.Builder()
                    .enable(KotlinFeature.StrictNullChecks)
                    .build(),
            )
            .enable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
            )
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .withCoercionConfig(LogicalType.Integer) { config ->
                config.setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                config.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
            }
            .withCoercionConfig(LogicalType.Float) { config ->
                config.setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                config.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
            }
            .withCoercionConfig(LogicalType.Boolean) { config ->
                config.setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                config.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
            }
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()
    }
}
