// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agentpolicy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0031 D9 phase 4, layer 1: the in-process charter check is deny-by-default in every
 * failure direction — a tampered grant, a disabled agent, an explicit object-shaped deny, a
 * missing resource, or an unparseable document each deny. Phase 4 adds the parametrisation
 * contract: the SAME verifier class grants a money-path agent its own (tier, resource)
 * namespace from a different fixture, and still binds flaky-test-hunter's phase-3 grant.
 */
class AgentCharterGrantVerifierTest {

    private val flakyVerifier = AgentCharterGrantVerifier(AgentPolicyConfig.flakyTestHunter())

    @Test
    fun `the packaged agentsyaml grants flaky-test-hunter the write_proposal github-pr grant`() {
        assertThat(flakyVerifier.checkGrant()).isEqualTo(CharterGrant.Granted)
    }

    @Test
    fun `a charter whose write_proposal grant was removed denies with charter_denied`() {
        val verifier = AgentCharterGrantVerifier(
            AgentPolicyConfig.flakyTestHunter(),
            resourcePath = "governance-fixtures/agents-tampered.yaml",
        )

        assertThat(verifier.checkGrant())
            .isEqualTo(CharterGrant.Denied(AgentPolicyVocabulary.REASON_CHARTER_DENIED))
    }

    @Test
    fun `a disabled agent denies with charter_denied even though the grant is present`() {
        val verifier = AgentCharterGrantVerifier(
            AgentPolicyConfig.flakyTestHunter(),
            resourcePath = "governance-fixtures/agents-disabled.yaml",
        )

        assertThat(verifier.checkGrant())
            .isEqualTo(CharterGrant.Denied(AgentPolicyVocabulary.REASON_CHARTER_DENIED))
    }

    @Test
    fun `an explicit object-shaped deny entry beats the grant`() {
        val verifier = AgentCharterGrantVerifier(
            AgentPolicyConfig.flakyTestHunter(),
            resourcePath = "governance-fixtures/agents-deny-overrides.yaml",
        )

        assertThat(verifier.checkGrant())
            .isEqualTo(CharterGrant.Denied(AgentPolicyVocabulary.REASON_CHARTER_DENIED))
    }

    @Test
    fun `a missing charter resource denies with charter_unavailable`() {
        val verifier = AgentCharterGrantVerifier(
            AgentPolicyConfig.flakyTestHunter(),
            resourcePath = "governance-charters/no-such-charter.yaml",
        )

        assertThat(verifier.checkGrant())
            .isEqualTo(CharterGrant.Denied(AgentPolicyVocabulary.REASON_CHARTER_UNAVAILABLE))
    }

    @Test
    fun `an unparseable charter document denies with charter_unavailable`() {
        val verifier = AgentCharterGrantVerifier(
            AgentPolicyConfig.flakyTestHunter(),
            resourcePath = "governance-fixtures/agents-malformed.yaml",
        )

        assertThat(verifier.checkGrant())
            .isEqualTo(CharterGrant.Denied(AgentPolicyVocabulary.REASON_CHARTER_UNAVAILABLE))
    }

    // ---------------------------------------------------------------------------------------
    // Phase-4 parametrisation: a money-path agent with its own resource namespace.
    // ---------------------------------------------------------------------------------------

    private val moneyPathConfig = AgentPolicyConfig(
        agentId = "money-path-fixture-agent",
        resourceId = "payment-instruction",
        tier = "money_path_write",
        tool = "money.repair.propose",
    )

    @Test
    fun `a money-path agent is granted its own tier and resource namespace`() {
        val verifier = AgentCharterGrantVerifier(
            moneyPathConfig,
            resourcePath = "governance-fixtures/agents-money-path.yaml",
        )

        assertThat(verifier.checkGrant()).isEqualTo(CharterGrant.Granted)
    }

    @Test
    fun `a money-path agent asking for another agents resource denies with charter_denied`() {
        val verifier = AgentCharterGrantVerifier(
            moneyPathConfig,
            resourcePath = "governance-fixtures/agents-money-path.yaml",
        )
        val githubPrInput = PolicyDecisionInput(
            tool = "gh.pr.open",
            resource = "github-pr",
            tier = "write_proposal",
        )

        assertThat(verifier.checkGrant(githubPrInput))
            .isEqualTo(CharterGrant.Denied(AgentPolicyVocabulary.REASON_CHARTER_DENIED))
    }

    @Test
    fun `a hard-denied money-path tier denies even inside the agents own charter`() {
        val verifier = AgentCharterGrantVerifier(
            moneyPathConfig,
            resourcePath = "governance-fixtures/agents-money-path.yaml",
        )
        val executeInput = PolicyDecisionInput(
            tool = "money.post.ledger",
            resource = "payment-instruction",
            tier = "money_path_execute",
        )

        assertThat(verifier.checkGrant(executeInput))
            .isEqualTo(CharterGrant.Denied(AgentPolicyVocabulary.REASON_CHARTER_DENIED))
    }
}
