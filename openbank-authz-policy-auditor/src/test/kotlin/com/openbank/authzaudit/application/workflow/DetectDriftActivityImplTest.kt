// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.authzaudit.application.workflow

import com.openbank.authzaudit.domain.model.AuthzPolicyCheckType
import com.openbank.authzaudit.domain.model.AuthzPolicySnapshot
import com.openbank.authzaudit.domain.model.CharterAllowToken
import com.openbank.authzaudit.domain.model.CharterDenyPattern
import com.openbank.authzaudit.domain.model.FindingSeverity
import com.openbank.authzaudit.domain.model.PrincipalTypeComparison
import com.openbank.authzaudit.domain.model.RestBypassReference
import com.openbank.authzaudit.domain.model.UnwrappedAgentIdComparison
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Directly exercises the four implemented ADR-0167 checks against hand-built [AuthzPolicySnapshot]
 * fixtures -- the judgment layer PolicyScanAdapterTest deliberately does not cover, since the raw
 * scan itself never decides whether a signal is a real defect.
 */
class DetectDriftActivityImplTest {

    private val activity = DetectDriftActivityImpl()

    private val emptySnapshot = AuthzPolicySnapshot(
        regoFilesScanned = 0,
        emittedPrincipalTypes = emptySet(),
        principalTypeComparisons = emptyList(),
        unwrappedAgentIdComparisons = emptyList(),
        toolTiersVocabulary = emptySet(),
        charterAllowTokens = emptyList(),
        charterDenyPatterns = emptyList(),
        restBypassReferences = emptyList(),
    )

    // --- Check 1: UNREACHABLE_PRINCIPAL_TYPE_RULE ---------------------------------------------

    @Test
    fun `principal type value AuthorizeInterceptor never emits is flagged as unreachable`() {
        val snapshot = emptySnapshot.copy(
            emittedPrincipalTypes = setOf("ANONYMOUS", "AI_AGENT", "HUMAN"),
            principalTypeComparisons = listOf(
                PrincipalTypeComparison(
                    file = "openbank-libs/governance/policies/rest.rego",
                    line = 175,
                    literalValue = "SERVICE",
                    snippet = "input.principal.type == \"SERVICE\"",
                ),
            ),
        )

        val findings = activity.detect(snapshot)

        assertThat(findings).hasSize(1)
        val finding = findings.single()
        assertThat(finding.checkType).isEqualTo(AuthzPolicyCheckType.UNREACHABLE_PRINCIPAL_TYPE_RULE)
        assertThat(finding.severity).isEqualTo(FindingSeverity.CRITICAL)
        assertThat(finding.filePath).isEqualTo("openbank-libs/governance/policies/rest.rego")
    }

    @Test
    fun `principal type value AuthorizeInterceptor does emit is not flagged`() {
        val snapshot = emptySnapshot.copy(
            emittedPrincipalTypes = setOf("ANONYMOUS", "AI_AGENT", "HUMAN"),
            principalTypeComparisons = listOf(
                PrincipalTypeComparison(
                    file = "openbank-libs/governance/policies/rest.rego",
                    line = 42,
                    literalValue = "HUMAN",
                    snippet = "input.principal.type == \"HUMAN\"",
                ),
            ),
        )

        assertThat(activity.detect(snapshot)).isEmpty()
    }

    @Test
    fun `empty emitted principal types produces no finding rather than flagging everything`() {
        // A parse failure upstream (AuthorizeInterceptor.kt missing/moved) yields an empty emitted
        // set -- must not be misread as "nothing is reachable" and flag every comparison.
        val snapshot = emptySnapshot.copy(
            emittedPrincipalTypes = emptySet(),
            principalTypeComparisons = listOf(
                PrincipalTypeComparison("rest.rego", 1, "SERVICE", "input.principal.type == \"SERVICE\""),
            ),
        )

        assertThat(activity.detect(snapshot)).isEmpty()
    }

    // --- Check 2: AGENT_ID_PREFIX_MISMATCH -----------------------------------------------------

    @Test
    fun `unwrapped input agent comparison is flagged`() {
        val snapshot = emptySnapshot.copy(
            unwrappedAgentIdComparisons = listOf(
                UnwrappedAgentIdComparison(
                    file = "openbank-infra/opa/policies/agents.rego",
                    line = 33,
                    snippet = "input.agent == a.id",
                ),
            ),
        )

        val findings = activity.detect(snapshot)

        assertThat(findings).hasSize(1)
        assertThat(findings.single().checkType).isEqualTo(AuthzPolicyCheckType.AGENT_ID_PREFIX_MISMATCH)
        assertThat(findings.single().severity).isEqualTo(FindingSeverity.CRITICAL)
    }

    @Test
    fun `no unwrapped comparisons in the snapshot produces no finding`() {
        // The already-fixed trim_prefix(input.agent, "agent:") form never reaches the snapshot in
        // the first place -- PolicyScanAdapterTest covers that exclusion at the scan layer.
        assertThat(activity.detect(emptySnapshot)).isEmpty()
    }

    // --- Check 3: CHARTER_TOOL_TIER_DRIFT ------------------------------------------------------

    @Test
    fun `charter allow token not registered in tool_tiers is flagged, the registered one is not`() {
        val snapshot = emptySnapshot.copy(
            toolTiersVocabulary = setOf("query.ledger.readonly", "flags.write"),
            charterAllowTokens = listOf(
                CharterAllowToken("compliance-officer", "flags.write"),
                CharterAllowToken("compliance-officer", "flags.wrote"),
            ),
        )

        val findings = activity.detect(snapshot)

        assertThat(findings).hasSize(1)
        val finding = findings.single()
        assertThat(finding.checkType).isEqualTo(AuthzPolicyCheckType.CHARTER_TOOL_TIER_DRIFT)
        assertThat(finding.severity).isEqualTo(FindingSeverity.WARNING)
        assertThat(finding.title).contains("flags.wrote")
        assertThat(finding.title).doesNotContain("'flags.write'")
    }

    @Test
    fun `allow token in a namespace tool_tiers has never used is not flagged as drift`() {
        // A charter-local action-proposal verb in a namespace tool_tiers never registers at all is
        // a different, legitimate vocabulary -- not a typo this shallow check can identify.
        val snapshot = emptySnapshot.copy(
            toolTiersVocabulary = setOf("query.ledger.readonly"),
            charterAllowTokens = listOf(CharterAllowToken("ui-assistant", "draft.ticket")),
        )

        assertThat(activity.detect(snapshot)).isEmpty()
    }

    @Test
    fun `deny glob matching nothing in the fleet vocabulary is flagged, one that matches is not`() {
        val snapshot = emptySnapshot.copy(
            toolTiersVocabulary = setOf("query.ledger.readonly"),
            charterDenyPatterns = listOf(
                CharterDenyPattern("compliance-officer", "money.*"),
                CharterDenyPattern("compliance-officer", "query.*"),
            ),
        )

        val findings = activity.detect(snapshot)

        assertThat(findings).hasSize(1)
        assertThat(findings.single().title).contains("money.*")
    }

    // --- Check 4: REST_BYPASSES_AGENTS_ALLOW ---------------------------------------------------

    @Test
    fun `charter_allowed reference outside agents rego is flagged`() {
        val snapshot = emptySnapshot.copy(
            restBypassReferences = listOf(
                RestBypassReference(
                    file = "openbank-libs/governance/policies/rest.rego",
                    line = 12,
                    snippet = "agents.charter_allowed(input.agent, input.tool)",
                ),
            ),
        )

        val findings = activity.detect(snapshot)

        assertThat(findings).hasSize(1)
        assertThat(findings.single().checkType).isEqualTo(AuthzPolicyCheckType.REST_BYPASSES_AGENTS_ALLOW)
        assertThat(findings.single().severity).isEqualTo(FindingSeverity.CRITICAL)
    }

    @Test
    fun `no rest bypass references in the snapshot produces no finding`() {
        assertThat(activity.detect(emptySnapshot)).isEmpty()
    }
}
