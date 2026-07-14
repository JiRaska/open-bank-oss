// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.authzaudit.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class AuthzPolicyModelsTest {

    @Test
    fun `AuthzPolicyFinding defaults to OPEN status`() {
        val finding = AuthzPolicyFinding(
            id = "test-id",
            checkType = AuthzPolicyCheckType.UNREACHABLE_PRINCIPAL_TYPE_RULE,
            severity = FindingSeverity.CRITICAL,
            detectedAt = Instant.now(),
            title = "rest.rego:175 gates a rule on principal.type == \"SERVICE\", which is never emitted",
            component = "openbank-libs/governance/policies/rest.rego",
            filePath = "openbank-libs/governance/policies/rest.rego",
            rawMetricValue = BigDecimal.ONE,
            threshold = BigDecimal.ZERO,
        )
        assertThat(finding.status).isEqualTo(FindingStatus.OPEN)
        assertThat(finding.rootCause).isNull()
        assertThat(finding.proposalUrl).isNull()
    }

    @Test
    fun `AuthzPolicyReport counts proposed findings`() {
        val now = Instant.now()
        val finding = AuthzPolicyFinding(
            id = "f1",
            checkType = AuthzPolicyCheckType.AGENT_ID_PREFIX_MISMATCH,
            severity = FindingSeverity.CRITICAL,
            detectedAt = now,
            title = "agents.rego:33 compares input.agent directly without a nearby trim_prefix wrap",
            component = "openbank-infra/opa/policies/agents.rego",
            filePath = "openbank-infra/opa/policies/agents.rego",
            rawMetricValue = BigDecimal.ONE,
            threshold = BigDecimal.ZERO,
            status = FindingStatus.PROPOSED,
        )
        val report = AuthzPolicyReport(
            runId = "run-1",
            startedAt = now,
            completedAt = now,
            regoFilesScanned = 4,
            findingsDetected = listOf(finding),
            findingsProposed = 1,
            tokensUsed = 0,
            trigger = RunTrigger.SCHEDULED,
        )
        assertThat(report.findingsProposed).isEqualTo(1)
        assertThat(report.findingsDetected).hasSize(1)
        assertThat(report.regoFilesScanned).isEqualTo(4)
    }

    @Test
    fun `AuthzPolicyCheckType enum covers the four implemented ADR-0167 checks`() {
        assertThat(AuthzPolicyCheckType.entries).containsExactlyInAnyOrder(
            AuthzPolicyCheckType.UNREACHABLE_PRINCIPAL_TYPE_RULE,
            AuthzPolicyCheckType.AGENT_ID_PREFIX_MISMATCH,
            AuthzPolicyCheckType.CHARTER_TOOL_TIER_DRIFT,
            AuthzPolicyCheckType.REST_BYPASSES_AGENTS_ALLOW,
        )
    }

    @Test
    fun `PrincipalTypeComparison carries the literal value a rule was gated on`() {
        val comparison = PrincipalTypeComparison(
            file = "openbank-libs/governance/policies/rest.rego",
            line = 175,
            literalValue = "SERVICE",
            snippet = "input.principal.type == \"SERVICE\"",
        )
        assertThat(comparison.literalValue).isEqualTo("SERVICE")
        assertThat(comparison.line).isEqualTo(175)
    }

    @Test
    fun `CharterDenyPattern and CharterAllowToken tag which charter they belong to`() {
        val allow = CharterAllowToken(agentId = "compliance-officer", token = "flags.write")
        val deny = CharterDenyPattern(agentId = "ui-assistant", pattern = "*.write")
        assertThat(allow.agentId).isEqualTo("compliance-officer")
        assertThat(deny.pattern).isEqualTo("*.write")
    }

    @Test
    fun `AuthzPolicySnapshot defaults to empty collections when nothing is found`() {
        val snapshot = AuthzPolicySnapshot(
            regoFilesScanned = 0,
            emittedPrincipalTypes = emptySet(),
            principalTypeComparisons = emptyList(),
            unwrappedAgentIdComparisons = emptyList(),
            toolTiersVocabulary = emptySet(),
            charterAllowTokens = emptyList(),
            charterDenyPatterns = emptyList(),
            restBypassReferences = emptyList(),
        )
        assertThat(snapshot.principalTypeComparisons).isEmpty()
        assertThat(snapshot.charterAllowTokens).isEmpty()
    }
}
