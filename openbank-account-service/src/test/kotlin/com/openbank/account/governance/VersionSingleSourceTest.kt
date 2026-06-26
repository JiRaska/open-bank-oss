package com.openbank.account.governance

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Enforces the two decoupled version axes for this service (ADR-0048, which
 * amends ADR-0029 D2): version.txt is the release/artifact SemVer (read by
 * build.gradle.kts into quarkus.application.version), while the OpenAPI
 * contract's info.version is the API-contract SemVer. They are NO LONGER
 * required to be equal — a behaviour change bumps version.txt without touching
 * the contract, and a contract change bumps info.version on its own cadence.
 *
 * This runs without booting the application (no containers): it guards each
 * axis as a well-formed SemVer string. The stricter api_invariant
 * (major(openapi.info.version) == openbank.api.version == URL /api/v{N})
 * lands with the fleet-wide openapi baseline migration, not here.
 */
class VersionSingleSourceTest {

    private val serviceVersion = File("version.txt").readText().trim()

    @Test
    fun `version_txt is a valid SemVer string`() {
        assertThat(serviceVersion)
            .matches("""\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?""")
    }

    @Test
    fun `openapi info_version is a valid SemVer string`() {
        assertThat(readOpenApiInfoVersion())
            .`as`("openapi.yaml info.version must be a SemVer API-contract version (ADR-0048)")
            .matches("""\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?""")
    }

    /** Extracts the first `version:` line inside the top-level `info:` block. */
    private fun readOpenApiInfoVersion(): String {
        val lines = File("src/main/resources/openapi.yaml").readLines()
        val infoIdx = lines.indexOfFirst { it.matches(Regex("""^info:\s*$""")) }
        require(infoIdx >= 0) { "no top-level info: block in openapi.yaml" }
        for (i in (infoIdx + 1) until lines.size) {
            val line = lines[i]
            // Stop at the next top-level key (column-0, non-comment).
            if (line.isNotEmpty() && !line[0].isWhitespace() && !line.startsWith("#")) break
            val m = Regex("""^\s+version:\s*"?([^"\s]+)"?\s*$""").find(line)
            if (m != null) return m.groupValues[1]
        }
        error("no info.version found in openapi.yaml")
    }
}
