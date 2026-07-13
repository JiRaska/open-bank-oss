// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.docstruth.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class DocsTruthModelsTest {

    @Test
    fun `DocsTruthFinding defaults to OPEN status`() {
        val finding = DocsTruthFinding(
            id = "test-id",
            checkType = DocsTruthCheckType.PLANNED_ARTIFACT_ALREADY_SHIPPED,
            severity = FindingSeverity.CRITICAL,
            detectedAt = Instant.now(),
            title = "ADR-0140 claims `OnlineFeatureStore` does not exist yet, but it was found",
            component = "ADR-0140",
            adrPath = "docs/adr/0140-feature-store-topology.md",
            rawMetricValue = BigDecimal.ONE,
            threshold = BigDecimal.ZERO,
        )
        assertThat(finding.status).isEqualTo(FindingStatus.OPEN)
        assertThat(finding.rootCause).isNull()
        assertThat(finding.proposalUrl).isNull()
    }

    @Test
    fun `DocsTruthReport counts proposed findings`() {
        val now = Instant.now()
        val finding = DocsTruthFinding(
            id = "f1",
            checkType = DocsTruthCheckType.SHIPPED_ARTIFACT_MISSING,
            severity = FindingSeverity.CRITICAL,
            detectedAt = now,
            title = "ADR-0166 claims Delivery-Status: Shipped but its artifact was not found",
            component = "ADR-0166",
            adrPath = "docs/adr/0166-docs-truth-agent-ai-agent.md",
            rawMetricValue = BigDecimal.ONE,
            threshold = BigDecimal.ZERO,
            status = FindingStatus.PROPOSED,
        )
        val report = DocsTruthReport(
            runId = "run-1",
            startedAt = now,
            completedAt = now,
            adrsScanned = 154,
            findingsDetected = listOf(finding),
            findingsProposed = 1,
            tokensUsed = 0,
            trigger = RunTrigger.SCHEDULED,
        )
        assertThat(report.findingsProposed).isEqualTo(1)
        assertThat(report.findingsDetected).hasSize(1)
        assertThat(report.adrsScanned).isEqualTo(154)
    }

    @Test
    fun `DocsTruthCheckType enum covers all three ADR-0166 checks`() {
        assertThat(DocsTruthCheckType.values()).containsExactlyInAnyOrder(
            DocsTruthCheckType.SHIPPED_ARTIFACT_MISSING,
            DocsTruthCheckType.PLANNED_ARTIFACT_ALREADY_SHIPPED,
            DocsTruthCheckType.ENFORCEMENT_STATUS_MISMATCH,
        )
    }

    @Test
    fun `ClaimedArtifact tags whether the ADR claims the artifact does not exist yet`() {
        val notYet = ClaimedArtifact(name = "OnlineFeatureStore", claimedNotYetBuilt = true)
        val shipped = ClaimedArtifact(name = "WorkflowLivenessWatchdog", claimedNotYetBuilt = false)
        assertThat(notYet.claimedNotYetBuilt).isTrue()
        assertThat(shipped.claimedNotYetBuilt).isFalse()
    }

    @Test
    fun `ArtifactExistence carries matched paths when found`() {
        val existence = ArtifactExistence(
            artifact = "OnlineFeatureStore",
            exists = true,
            matchedPaths = listOf("openbank-fraud-service/src/main/kotlin/.../OnlineFeatureStore.kt"),
        )
        assertThat(existence.exists).isTrue()
        assertThat(existence.matchedPaths).isNotEmpty()
    }

    @Test
    fun `AdrRecord defaults to empty claims when an ADR names none`() {
        val adr = AdrRecord(
            id = "ADR-0166",
            path = "docs/adr/0166-docs-truth-agent-ai-agent.md",
            title = "docs-truth-agent AI agent",
            deliveryStatus = AdrDeliveryStatus.PARTIAL,
            claimedArtifacts = emptyList(),
            claimedEnforcements = emptyList(),
        )
        assertThat(adr.claimedArtifacts).isEmpty()
        assertThat(adr.claimedEnforcements).isEmpty()
    }
}
