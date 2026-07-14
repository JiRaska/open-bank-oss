// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.authzaudit.infrastructure.adapter

import com.openbank.authzaudit.domain.model.CharterAllowToken
import com.openbank.authzaudit.domain.model.CharterDenyPattern
import com.openbank.authzaudit.infrastructure.config.AuthzPolicyAuditorConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Exercises [PolicyScanAdapter]'s regex-based scanners (AuthorizeInterceptorScanner,
 * PolicyTextScanner, AgentsYamlScanner) via the public `scan()` entry point against a synthetic
 * repo-checkout fixture under a [TempDir] -- these private objects have no direct test seam, so a
 * fixture rooted at `config.repoRoot()` mirroring the real relative paths (RegoFiles.REGO_DIRS,
 * AgentsYamlScanner.REL_PATH, AuthorizeInterceptorScanner.REL_PATH) is the only way to reach them.
 * Whether a raw signal here turns into an actual finding is DetectDriftActivityImplTest's job.
 */
class PolicyScanAdapterTest {

    private fun adapterFor(root: Path): PolicyScanAdapter {
        val config = mockk<AuthzPolicyAuditorConfig>()
        every { config.repoRoot() } returns root.toString()
        return PolicyScanAdapter(config)
    }

    @Test
    fun `principal type comparison is captured from a live rule but not from a comment`(@TempDir tempDir: Path) {
        val policiesDir = tempDir.resolve("openbank-infra/opa/policies").createDirectories()
        policiesDir.resolve("rest.rego").writeText(
            """
            |package rest
            |
            |# comment mentioning principal.type == "SERVICE" -- must not self-trigger
            |allow {
            |    input.principal.type == "SERVICE"
            |}
            """.trimMargin(),
        )

        val snapshot = runBlocking { adapterFor(tempDir).scan() }

        assertThat(snapshot.principalTypeComparisons).hasSize(1)
        val comparison = snapshot.principalTypeComparisons.single()
        assertThat(comparison.literalValue).isEqualTo("SERVICE")
        assertThat(comparison.file).isEqualTo("openbank-infra/opa/policies/rest.rego")
        assertThat(comparison.line).isEqualTo(5)
    }

    @Test
    fun `input agent equality without a nearby trim_prefix wrap is flagged, the wrapped form is not`(
        @TempDir tempDir: Path,
    ) {
        val policiesDir = tempDir.resolve("openbank-libs/governance/policies").createDirectories()
        policiesDir.resolve("rest.rego").writeText(
            """
            |package rest
            |
            |allow_missing_wrap {
            |    input.agent == a.id
            |}
            |
            |allow_already_fixed {
            |    trim_prefix(input.agent, "agent:") == a.id
            |}
            """.trimMargin(),
        )

        val snapshot = runBlocking { adapterFor(tempDir).scan() }

        assertThat(snapshot.unwrappedAgentIdComparisons).hasSize(1)
        val comparison = snapshot.unwrappedAgentIdComparisons.single()
        assertThat(comparison.snippet).isEqualTo("input.agent == a.id")
        assertThat(comparison.file).isEqualTo("openbank-libs/governance/policies/rest.rego")
    }

    @Test
    fun `charter_allowed reference is flagged outside agents rego, excluded inside it and in test files`(
        @TempDir tempDir: Path,
    ) {
        val policiesDir = tempDir.resolve("openbank-infra/opa/policies").createDirectories()
        policiesDir.resolve("agents.rego").writeText(
            """
            |package agents
            |
            |charter_allowed(agentId, tool) {
            |    # defines the predicate this file is the one legitimate owner of
            |    true
            |}
            """.trimMargin(),
        )
        policiesDir.resolve("rest.rego").writeText(
            """
            |package rest
            |
            |bypass {
            |    agents.charter_allowed(input.agent, input.tool)
            |}
            """.trimMargin(),
        )
        // A unit test exercising the predicate directly must NOT be scanned -- ruleFiles already
        // drops every *_test.rego (the LOW fix: this check now reuses ruleFiles, not regoFiles).
        policiesDir.resolve("rest_test.rego").writeText(
            """
            |package rest
            |
            |test_bypass_allows_when_charter_allows {
            |    agents.charter_allowed("compliance-officer", "flags.write")
            |}
            """.trimMargin(),
        )

        val snapshot = runBlocking { adapterFor(tempDir).scan() }

        assertThat(snapshot.restBypassReferences).hasSize(1)
        assertThat(snapshot.restBypassReferences.single().file).isEqualTo("openbank-infra/opa/policies/rest.rego")
    }

    @Test
    fun `agents yaml scan parses tool_tiers vocabulary and a flat-list charter's allow deny tokens`(
        @TempDir tempDir: Path,
    ) {
        val governanceDir = tempDir.resolve("openbank-libs/governance").createDirectories()
        governanceDir.resolve("agents.yaml").writeText(
            """
            |schema_version: 1
            |
            |tool_tiers:
            |  read:
            |    - query.ledger.readonly
            |    - flags.write
            |
            |agents:
            |  - id: compliance-officer
            |    plane: control
            |    tools:
            |      allow: [flags.write, flags.wrote]
            |      deny:  ["money.*"]
            """.trimMargin(),
        )

        val snapshot = runBlocking { adapterFor(tempDir).scan() }

        assertThat(snapshot.toolTiersVocabulary).containsExactlyInAnyOrder("query.ledger.readonly", "flags.write")
        assertThat(snapshot.charterAllowTokens).containsExactlyInAnyOrder(
            CharterAllowToken("compliance-officer", "flags.write"),
            CharterAllowToken("compliance-officer", "flags.wrote"),
        )
        assertThat(snapshot.charterDenyPatterns).containsExactly(
            CharterDenyPattern("compliance-officer", "money.*"),
        )
    }

    // Hardens PolicyScanAdapter's own brace-depth counter: a nested `when` block closes on its own
    // line ("        }") BEFORE principalType()'s real closing brace. The old "first bare '}' wins"
    // heuristic would have stopped scanning right there and silently dropped "AI_AGENT", which is
    // declared after the nested block closes.
    @Test
    fun `emitted principal types are read past a nested block's own closing brace`(@TempDir tempDir: Path) {
        val interceptorDir = tempDir.resolve("openbank-libs-runtime/src/main/kotlin/com/openbank/libs/authz")
            .createDirectories()
        interceptorDir.resolve("AuthorizeInterceptor.kt").writeText(
            """
            |package com.openbank.libs.authz
            |
            |class AuthorizeInterceptor {
            |    private fun principalType(): String {
            |        val result = when {
            |            isAnonymous -> "ANONYMOUS"
            |            else -> "HUMAN"
            |        }
            |        return if (isAgent) "AI_AGENT" else result
            |    }
            |}
            """.trimMargin(),
        )

        val snapshot = runBlocking { adapterFor(tempDir).scan() }

        assertThat(snapshot.emittedPrincipalTypes).containsExactlyInAnyOrder("ANONYMOUS", "HUMAN", "AI_AGENT")
    }
}
