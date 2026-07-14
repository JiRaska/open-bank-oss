// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.docstruth.infrastructure.adapter

import com.openbank.docstruth.infrastructure.config.DocsTruthAgentConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class GovernanceRulesAdapterTest {

    @TempDir
    lateinit var repoDir: Path

    private fun writeRulesYaml(content: String) {
        File(repoDir.toFile(), "openbank-libs/governance").apply { mkdirs() }
            .resolve("rules.yaml")
            .writeText(content)
    }

    private fun adapter(): GovernanceRulesAdapter {
        val config = mockk<DocsTruthAgentConfig>()
        every { config.repoRoot() } returns repoDir.toString()
        return GovernanceRulesAdapter(config)
    }

    // NOTE: every test body below uses a block body (`{ runBlocking { ... } }`), never the
    // expression-body form (`= runBlocking { ... }` right after the parameter list) — that form
    // infers the function's return type from runBlocking's result (an AssertJ assert object
    // here), which is non-Unit, so JUnit5 silently skips the test instead of running it (see
    // CLAUDE.md's documented pitfall).

    @Test
    fun `returns empty map when rules yaml does not exist`() {
        runBlocking {
            val result = adapter().enforcedStatusFor(setOf("db-migration"))
            assertThat(result).isEmpty()
        }
    }

    @Test
    fun `resolves the enforced value nearest the exact gate key`() {
        runBlocking {
            writeRulesYaml(
                """
                rules:
                  db_change:
                    gate: db-migration
                    enforced: advisory
                  money_path_or_trust_boundary_change:
                    gate: threat-model
                    enforced: enforce
                """.trimIndent(),
            )

            val result = adapter().enforcedStatusFor(setOf("db-migration", "threat-model"))

            assertThat(result["db-migration"]).isEqualTo("advisory")
            assertThat(result["threat-model"]).isEqualTo("enforce")
        }
    }

    // Regression test for the wrong-gate-attribution review finding: a bare substring match
    // (`lines.indexOfFirst { it.contains(gate) }`) would resolve "threat-model" against the FIRST
    // line containing that text anywhere in the file — including inside "threat-model-diff",
    // which contains "threat-model" as a substring — landing on the wrong rule's `enforced:`
    // value. Anchoring on the exact `gate: <name>` key must not conflate the two.
    @Test
    fun `does not attribute a substring gate name's enforced value to a different, longer gate name`() {
        runBlocking {
            writeRulesYaml(
                """
                rules:
                  trust_boundary_diff_change:
                    gate: threat-model-diff
                    enforced: advisory
                  money_path_or_trust_boundary_change:
                    gate: threat-model
                    enforced: enforce
                """.trimIndent(),
            )

            val result = adapter().enforcedStatusFor(setOf("threat-model", "threat-model-diff"))

            assertThat(result["threat-model"]).isEqualTo("enforce")
            assertThat(result["threat-model-diff"]).isEqualTo("advisory")
        }
    }

    // Regression test for the exact real-world shape found in rules.yaml today: the gate name
    // "version-bump" is legitimately reused by two distinct rules (service_code_change and
    // admin_ui_code_change). When both agree, report the shared value...
    @Test
    fun `reports the shared value when a reused gate name resolves consistently everywhere`() {
        runBlocking {
            writeRulesYaml(
                """
                rules:
                  service_code_change:
                    gate: version-bump
                    enforced: advisory
                  admin_ui_code_change:
                    gate: version-bump
                    enforced: advisory
                """.trimIndent(),
            )

            val result = adapter().enforcedStatusFor(setOf("version-bump"))

            assertThat(result["version-bump"]).isEqualTo("advisory")
        }
    }

    // ...but when a reused gate name resolves to CONFLICTING values across its multiple
    // definitions, refuse to attribute either one rather than silently pick the first (wrong
    // attribution is worse than no finding).
    @Test
    fun `refuses to attribute a value when a reused gate name has conflicting enforced states`() {
        runBlocking {
            writeRulesYaml(
                """
                rules:
                  service_code_change:
                    gate: version-bump
                    enforced: advisory
                  admin_ui_code_change:
                    gate: version-bump
                    enforced: enforce
                """.trimIndent(),
            )

            val result = adapter().enforcedStatusFor(setOf("version-bump"))

            assertThat(result).doesNotContainKey("version-bump")
        }
    }

    @Test
    fun `ignores a bare mention of the gate name in a comment that is not the gate key itself`() {
        runBlocking {
            writeRulesYaml(
                """
                rules:
                  # unrelated early comment mentioning db-migration in passing, far from the real rule
                  other_rule:
                    gate: something-else
                    enforced: enforce
                  db_change:
                    gate: db-migration
                    enforced: advisory
                """.trimIndent(),
            )

            val result = adapter().enforcedStatusFor(setOf("db-migration"))

            assertThat(result["db-migration"]).isEqualTo("advisory")
        }
    }

    @Test
    fun `returns no entry for an unknown gate name`() {
        runBlocking {
            writeRulesYaml(
                """
                rules:
                  db_change:
                    gate: db-migration
                    enforced: advisory
                """.trimIndent(),
            )

            val result = adapter().enforcedStatusFor(setOf("nonexistent-gate"))

            assertThat(result).doesNotContainKey("nonexistent-gate")
        }
    }
}
