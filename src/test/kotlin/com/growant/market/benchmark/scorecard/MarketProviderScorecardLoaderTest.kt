package com.growant.market.benchmark.scorecard

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class MarketProviderScorecardLoaderTest {
    private val loader = MarketProviderScorecardLoader()

    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `rejects an unknown manifest field`() {
        val directory = copyCatalog()
        mutateManifest(directory) { json ->
            json.replaceFirst("\"schemaVersion\": 1,", "\"schemaVersion\": 1,\n  \"unknownField\": true,")
        }

        assertStrictJsonFailure(directory, "Unrecognized property \"unknownField\"")
    }

    @Test
    fun `rejects a duplicate JSON property`() {
        val directory = copyCatalog()
        mutateManifest(directory) { json ->
            json.replaceFirst("\"schemaVersion\": 1,", "\"schemaVersion\": 1,\n  \"schemaVersion\": 1,")
        }

        assertStrictJsonFailure(directory, "Duplicate Object property")
    }

    @Test
    fun `rejects trailing JSON content`() {
        val directory = copyCatalog()
        mutateManifest(directory) { json -> "$json{}\n" }

        assertStrictJsonFailure(directory, "Trailing token")
    }

    @Test
    fun `rejects a missing required manifest property`() {
        val directory = copyCatalog()
        mutateManifest(directory) { json ->
            json.replaceFirst(Regex("  \\\"scorecardVersion\\\": \\\"[^\\\"]+\\\",\\n"), "")
        }

        assertStrictJsonFailure(directory, "scorecardVersion")
    }

    @Test
    fun `rejects null for a non-null manifest property`() {
        val directory = copyCatalog()
        mutateManifest(directory) { json ->
            json.replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": null")
        }

        assertStrictJsonFailure(directory, "schemaVersion")
    }

    @Test
    fun `rejects an unknown enum value`() {
        val directory = copyCatalog()
        mutateManifest(directory) { json ->
            json.replaceFirst("\"state\": \"DRAFT\"", "\"state\": \"READY\"")
        }

        assertStrictJsonFailure(directory, "READY")
    }

    @Test
    fun `rejects a numeric string for an integer`() {
        val directory = copyCatalog()
        mutateManifest(directory) { json ->
            json.replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": \"1\"")
        }

        assertStrictJsonFailure(directory, "Cannot coerce String value")
    }

    @Test
    fun `rejects a numeric string for a decimal`() {
        val directory = copyCatalog()
        mutateManifest(directory) { json ->
            json.replaceFirst(
                "\"maxDifferencePointsInclusive\": 3",
                "\"maxDifferencePointsInclusive\": \"3\"",
            )
        }

        assertStrictJsonFailure(directory, "Cannot coerce String value")
    }

    @Test
    fun `rejects a boolean string`() {
        val directory = copyCatalog()
        mutateScorecard(directory, REALTIME_FILE) { json ->
            json.replaceFirst("\"scoredRunsAllowed\": false", "\"scoredRunsAllowed\": \"false\"")
        }

        assertStrictJsonFailure(directory, "Cannot coerce String value")
    }

    @Test
    fun `rejects an unknown nested role field after verifying exact bytes`() {
        val directory = copyCatalog()
        mutateScorecard(directory, REALTIME_FILE) { json ->
            json.replaceFirst("\"schemaVersion\": 1,", "\"schemaVersion\": 1,\n  \"unknownRoleField\": true,")
        }

        assertStrictJsonFailure(directory, "Unrecognized property \"unknownRoleField\"")
    }

    @Test
    fun `requires explicitly null nullable hard gate fields`() {
        val directory = copyCatalog()
        mutateScorecard(directory, REALTIME_FILE) { json ->
            json.replaceFirst("      \"metricId\": null,\n", "")
        }

        assertThatThrownBy { loader.loadForAuthoring(directory) }
            .isInstanceOf(MarketProviderScorecardValidationException::class.java)
            .hasMessageContaining("must explicitly contain nullable fields: metricId")
    }

    @Test
    fun `hashes exact role bytes before parsing`() {
        val directory = copyCatalog()
        val scorecard = directory.resolve(REALTIME_FILE)
        scorecard.writeBytes(scorecard.readBytes() + '\n'.code.toByte())

        assertThatThrownBy { loader.loadForAuthoring(directory) }
            .isInstanceOf(MarketProviderScorecardValidationException::class.java)
            .hasMessageContaining("SHA-256 does not match the manifest")

        refreshManifestHash(directory, REALTIME_FILE)
        val loaded = loader.loadForAuthoring(directory)
        val entry = loaded.manifest.entries.single { it.fileName == REALTIME_FILE }
        assertThat(entry.sha256).isEqualTo(sha256(scorecard.readBytes()))
    }

    @Test
    fun `reports the SHA-256 of the exact manifest bytes`() {
        val directory = copyCatalog()
        val expected = sha256(directory.resolve("manifest.json").readBytes())

        assertThat(loader.loadForAuthoring(directory).manifestSha256).isEqualTo(expected)
    }

    @Test
    fun `rejects a UTF-8 byte order mark`() {
        val directory = copyCatalog()
        val manifest = directory.resolve("manifest.json")
        manifest.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + manifest.readBytes())

        assertThatThrownBy { loader.loadForAuthoring(directory) }
            .isInstanceOf(MarketProviderScorecardValidationException::class.java)
            .hasMessageContaining("must be UTF-8 without a byte-order mark")
    }

    @Test
    fun `rejects CRLF line endings`() {
        val directory = copyCatalog()
        val manifest = directory.resolve("manifest.json")
        manifest.writeText(manifest.readText().replace("\n", "\r\n"))

        assertThatThrownBy { loader.loadForAuthoring(directory) }
            .isInstanceOf(MarketProviderScorecardValidationException::class.java)
            .hasMessageContaining("must use LF line endings")
    }

    @Test
    fun `rejects a manifest path escape`() {
        val directory = copyCatalog()

        assertThatThrownBy { loader.loadForAuthoring(directory, "../manifest.json") }
            .isInstanceOf(MarketProviderScorecardValidationException::class.java)
            .hasMessageContaining("manifestFileName must be a relative basename")
    }

    @Test
    fun `rejects a scorecard path escape from the manifest`() {
        val directory = copyCatalog()
        mutateManifest(directory) { json ->
            json.replaceFirst("\"fileName\": \"$REALTIME_FILE\"", "\"fileName\": \"../outside.json\"")
        }

        assertThatThrownBy { loader.loadForAuthoring(directory) }
            .isInstanceOf(MarketProviderScorecardValidationException::class.java)
            .hasMessageContaining("fileName must be a relative JSON basename")
    }

    @Test
    fun `rejects a symbolic link manifest`() {
        val directory = tempDirectory.resolve("symlink-manifest")
        Files.createDirectories(directory)
        Files.createSymbolicLink(
            directory.resolve("manifest.json"),
            CATALOG_DIRECTORY.resolve("manifest.json").toAbsolutePath(),
        )

        assertThatThrownBy { loader.loadForAuthoring(directory) }
            .isInstanceOf(MarketProviderScorecardValidationException::class.java)
            .hasMessageContaining("manifest may not be a symbolic link")
    }

    @Test
    fun `rejects a symbolic link scorecard`() {
        val directory = copyCatalog()
        val scorecard = directory.resolve(REALTIME_FILE)
        Files.delete(scorecard)
        Files.createSymbolicLink(scorecard, CATALOG_DIRECTORY.resolve(REALTIME_FILE).toAbsolutePath())

        assertThatThrownBy { loader.loadForAuthoring(directory) }
            .isInstanceOf(MarketProviderScorecardValidationException::class.java)
            .hasMessageContaining("scorecard REALTIME_POC may not be a symbolic link")
    }

    private fun assertStrictJsonFailure(directory: Path, expectedMessage: String) {
        assertThatThrownBy { loader.loadForAuthoring(directory) }
            .isInstanceOf(MarketProviderScorecardValidationException::class.java)
            .hasMessageContaining("not valid strict scorecard JSON")
            .hasMessageContaining(expectedMessage)
    }

    private fun copyCatalog(): Path {
        val destination = tempDirectory.resolve("catalog")
        Files.walk(CATALOG_DIRECTORY).use { paths ->
            paths.forEach { source ->
                val target = destination.resolve(CATALOG_DIRECTORY.relativize(source).toString())
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target)
                } else {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        return destination
    }

    private fun mutateManifest(directory: Path, transform: (String) -> String) {
        val manifest = directory.resolve("manifest.json")
        manifest.writeText(transform(manifest.readText()), StandardCharsets.UTF_8)
    }

    private fun mutateScorecard(directory: Path, fileName: String, transform: (String) -> String) {
        val scorecard = directory.resolve(fileName)
        scorecard.writeText(transform(scorecard.readText()), StandardCharsets.UTF_8)
        refreshManifestHash(directory, fileName)
    }

    private fun refreshManifestHash(directory: Path, fileName: String) {
        val scorecardHash = sha256(directory.resolve(fileName).readBytes())
        val manifest = directory.resolve("manifest.json")
        val entryHash = Regex(
            "(\\\"fileName\\\": \\\"${Regex.escape(fileName)}\\\",\\s*\\\"sha256\\\": \\\")[0-9a-f]{64}(\\\")",
        )
        val original = manifest.readText()
        val updated = entryHash.replace(original) { match ->
            match.groupValues[1] + scorecardHash + match.groupValues[2]
        }
        check(updated != original) { "manifest entry not found for $fileName" }
        manifest.writeText(updated, StandardCharsets.UTF_8)
    }

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private companion object {
        val CATALOG_DIRECTORY: Path = Path.of("benchmark/market-provider-scorecards/v1")
        const val REALTIME_FILE = "benchmark-scorecard-realtime-poc.json"
    }
}
