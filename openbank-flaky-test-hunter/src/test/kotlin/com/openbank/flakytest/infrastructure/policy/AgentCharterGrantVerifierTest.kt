// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.infrastructure.policy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0031 D9 phase 3, layer 1: the in-process charter check is deny-by-default in every
 * failure direction — a tampered grant, a disabled agent, an explicit object-shaped deny, a
 * missing resource, or an unparseable document each deny, with only the REAL packaged
 * agents.yaml granting.
 */
class AgentCharterGrantVerifierTest {

    private val verifier = AgentCharterGrantVerifier()

    @Test
    fun `the packaged agentsyaml grants flaky-test-hunter the write_proposal github-pr grant`() {
        assertThat(verifier.checkPrOpenGrant()).isEqualTo(CharterGrant.Granted)
    }

    @Test
    fun `a charter whose write_proposal grant was removed denies with charter_denied`() {
        verifier.resourcePath = "governance-fixtures/agents-tampered.yaml"

        assertThat(verifier.checkPrOpenGrant())
            .isEqualTo(CharterGrant.Denied(PrPathGateVocabulary.REASON_CHARTER_DENIED))
    }

    @Test
    fun `a disabled agent denies with charter_denied even though the grant is present`() {
        verifier.resourcePath = "governance-fixtures/agents-disabled.yaml"

        assertThat(verifier.checkPrOpenGrant())
            .isEqualTo(CharterGrant.Denied(PrPathGateVocabulary.REASON_CHARTER_DENIED))
    }

    @Test
    fun `a missing or non-boolean enabled field denies even when the grant is present`() {
        val malformedValues = listOf(null, "null", "\"true\"", "\"false\"", "1", "0", "{}", "[]")
        for (value in malformedValues) {
            val enabled = value?.let { "\"enabled\":$it," }.orEmpty()
            val charter = """{"agents":[{"id":"flaky-test-hunter",$enabled
                "tools":{"allow":[{"tier":"write_proposal","resources":["github-pr"]}]}}]}"""

            assertThat(verifier.parse(charter))
                .describedAs("enabled=%s must not grant PR access", value ?: "missing")
                .isEqualTo(CharterGrant.Denied(PrPathGateVocabulary.REASON_CHARTER_DENIED))
        }
    }

    @Test
    fun `an explicit object-shaped deny entry beats the grant`() {
        verifier.resourcePath = "governance-fixtures/agents-deny-overrides.yaml"

        assertThat(verifier.checkPrOpenGrant())
            .isEqualTo(CharterGrant.Denied(PrPathGateVocabulary.REASON_CHARTER_DENIED))
    }

    @Test
    fun `a missing charter resource denies with charter_unavailable`() {
        verifier.resourcePath = "governance-charters/no-such-charter.yaml"

        assertThat(verifier.checkPrOpenGrant())
            .isEqualTo(CharterGrant.Denied(PrPathGateVocabulary.REASON_CHARTER_UNAVAILABLE))
    }

    @Test
    fun `an unparseable charter document denies with charter_unavailable`() {
        verifier.resourcePath = "governance-fixtures/agents-malformed.yaml"

        assertThat(verifier.checkPrOpenGrant())
            .isEqualTo(CharterGrant.Denied(PrPathGateVocabulary.REASON_CHARTER_UNAVAILABLE))
    }
}
