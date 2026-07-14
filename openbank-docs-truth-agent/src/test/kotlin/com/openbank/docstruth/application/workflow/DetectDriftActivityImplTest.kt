// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.docstruth.application.workflow

import com.openbank.docstruth.domain.model.AdrDeliveryStatus
import com.openbank.docstruth.domain.model.AdrRecord
import com.openbank.docstruth.domain.model.ArtifactExistence
import com.openbank.docstruth.domain.model.ClaimedArtifact
import com.openbank.docstruth.domain.model.ClaimedEnforcement
import com.openbank.docstruth.domain.model.DocsTruthCheckType
import com.openbank.docstruth.domain.model.DocsTruthSnapshot
import com.openbank.docstruth.domain.model.FindingSeverity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DetectDriftActivityImplTest {

    private val detector = DetectDriftActivityImpl()

    private fun adr(
        id: String = "ADR-0166",
        status: AdrDeliveryStatus = AdrDeliveryStatus.SHIPPED,
        artifacts: List<ClaimedArtifact> = emptyList(),
        enforcements: List<ClaimedEnforcement> = emptyList(),
    ) = AdrRecord(
        id = id,
        path = "docs/adr/${id.lowercase()}.md",
        title = "Title for $id",
        deliveryStatus = status,
        claimedArtifacts = artifacts,
        claimedEnforcements = enforcements,
    )

    // Check 1: a Shipped/Complete ADR whose claimed artifacts cannot be found anywhere else in
    // the repo — CRITICAL when NONE of the claimed artifacts are found.
    @Test
    fun `check 1 flags CRITICAL when a Shipped ADR's only claimed artifact is entirely missing`() {
        val record = adr(artifacts = listOf(ClaimedArtifact("GhostArtifact", claimedNotYetBuilt = false)))
        val snapshot = DocsTruthSnapshot(
            adrRecords = listOf(record),
            artifactExistence = mapOf(
                "GhostArtifact" to ArtifactExistence("GhostArtifact", exists = false, matchedPaths = emptyList()),
            ),
            gateEnforcementStatus = emptyMap(),
        )

        val findings = detector.detect(snapshot)

        assertThat(findings).hasSize(1)
        assertThat(findings.single().checkType).isEqualTo(DocsTruthCheckType.SHIPPED_ARTIFACT_MISSING)
        assertThat(findings.single().severity).isEqualTo(FindingSeverity.CRITICAL)
    }

    @Test
    fun `check 1 flags WARNING, not CRITICAL, when only some claimed artifacts are missing`() {
        val record = adr(
            artifacts = listOf(
                ClaimedArtifact("LedgerService", claimedNotYetBuilt = false),
                ClaimedArtifact("GhostArtifact", claimedNotYetBuilt = false),
            ),
        )
        val snapshot = DocsTruthSnapshot(
            adrRecords = listOf(record),
            artifactExistence = mapOf(
                "LedgerService" to ArtifactExistence("LedgerService", exists = true, matchedPaths = listOf("x.kt")),
                "GhostArtifact" to ArtifactExistence("GhostArtifact", exists = false, matchedPaths = emptyList()),
            ),
            gateEnforcementStatus = emptyMap(),
        )

        val findings = detector.detect(snapshot)

        assertThat(findings).hasSize(1)
        assertThat(findings.single().severity).isEqualTo(FindingSeverity.WARNING)
    }

    @Test
    fun `check 1 does not fire when every claimed artifact is found`() {
        val record = adr(artifacts = listOf(ClaimedArtifact("LedgerService", claimedNotYetBuilt = false)))
        val snapshot = DocsTruthSnapshot(
            adrRecords = listOf(record),
            artifactExistence = mapOf(
                "LedgerService" to ArtifactExistence("LedgerService", exists = true, matchedPaths = listOf("x.kt")),
            ),
            gateEnforcementStatus = emptyMap(),
        )

        assertThat(detector.detect(snapshot)).isEmpty()
    }

    @Test
    fun `check 1 does not fire for a Planned ADR even if its artifacts are missing`() {
        val record = adr(
            status = AdrDeliveryStatus.PLANNED,
            artifacts = listOf(ClaimedArtifact("NotBuiltYet", claimedNotYetBuilt = false)),
        )
        val snapshot = DocsTruthSnapshot(
            adrRecords = listOf(record),
            artifactExistence = mapOf(
                "NotBuiltYet" to ArtifactExistence("NotBuiltYet", exists = false, matchedPaths = emptyList()),
            ),
            gateEnforcementStatus = emptyMap(),
        )

        assertThat(detector.detect(snapshot)).isEmpty()
    }

    // Check 2 (the ADR-0139/ADR-0140 shape): a Planned/Partial ADR explicitly claims an artifact
    // does NOT exist yet, but a repo-wide scan finds it anyway.
    @Test
    fun `check 2 flags CRITICAL when a claimed-not-yet-built artifact is actually found`() {
        val record = adr(
            status = AdrDeliveryStatus.PLANNED,
            artifacts = listOf(ClaimedArtifact("OnlineFeatureStore", claimedNotYetBuilt = true)),
        )
        val snapshot = DocsTruthSnapshot(
            adrRecords = listOf(record),
            artifactExistence = mapOf(
                "OnlineFeatureStore" to ArtifactExistence(
                    "OnlineFeatureStore",
                    exists = true,
                    matchedPaths = listOf("openbank-fraud-service/.../OnlineFeatureStore.kt"),
                ),
            ),
            gateEnforcementStatus = emptyMap(),
        )

        val findings = detector.detect(snapshot)

        assertThat(findings).hasSize(1)
        assertThat(findings.single().checkType).isEqualTo(DocsTruthCheckType.PLANNED_ARTIFACT_ALREADY_SHIPPED)
        assertThat(findings.single().severity).isEqualTo(FindingSeverity.CRITICAL)
    }

    @Test
    fun `check 2 does not fire when the claimed-not-yet-built artifact truly does not exist`() {
        val record = adr(
            status = AdrDeliveryStatus.PLANNED,
            artifacts = listOf(ClaimedArtifact("StillUnbuilt", claimedNotYetBuilt = true)),
        )
        val snapshot = DocsTruthSnapshot(
            adrRecords = listOf(record),
            artifactExistence = mapOf(
                "StillUnbuilt" to ArtifactExistence("StillUnbuilt", exists = false, matchedPaths = emptyList()),
            ),
            gateEnforcementStatus = emptyMap(),
        )

        assertThat(detector.detect(snapshot)).isEmpty()
    }

    // Check 3: an ADR/doc claims a gate is (or is not) enforced, but rules.yaml's own
    // gate-graduation `enforced:` flag says otherwise.
    @Test
    fun `check 3 flags CRITICAL when the ADR claims enforced but rules yaml says advisory`() {
        val record = adr(enforcements = listOf(ClaimedEnforcement("docs-currency", claimedEnforced = true)))
        val snapshot = DocsTruthSnapshot(
            adrRecords = listOf(record),
            artifactExistence = emptyMap(),
            gateEnforcementStatus = mapOf("docs-currency" to "advisory"),
        )

        val findings = detector.detect(snapshot)

        assertThat(findings).hasSize(1)
        assertThat(findings.single().checkType).isEqualTo(DocsTruthCheckType.ENFORCEMENT_STATUS_MISMATCH)
        assertThat(findings.single().severity).isEqualTo(FindingSeverity.CRITICAL)
    }

    @Test
    fun `check 3 flags a mismatch when the ADR claims advisory-only but rules yaml says enforce`() {
        val record = adr(enforcements = listOf(ClaimedEnforcement("duplicate-yaml-keys", claimedEnforced = false)))
        val snapshot = DocsTruthSnapshot(
            adrRecords = listOf(record),
            artifactExistence = emptyMap(),
            gateEnforcementStatus = mapOf("duplicate-yaml-keys" to "enforce"),
        )

        val findings = detector.detect(snapshot)

        assertThat(findings).hasSize(1)
        assertThat(findings.single().checkType).isEqualTo(DocsTruthCheckType.ENFORCEMENT_STATUS_MISMATCH)
    }

    @Test
    fun `check 3 does not fire when the ADR's enforcement claim matches rules yaml`() {
        val record = adr(enforcements = listOf(ClaimedEnforcement("outbox-dispatch-enabled", claimedEnforced = true)))
        val snapshot = DocsTruthSnapshot(
            adrRecords = listOf(record),
            artifactExistence = emptyMap(),
            gateEnforcementStatus = mapOf("outbox-dispatch-enabled" to "enforce"),
        )

        assertThat(detector.detect(snapshot)).isEmpty()
    }

    @Test
    fun `check 3 does not fire when the gate's enforced status is unknown`() {
        val record = adr(enforcements = listOf(ClaimedEnforcement("no-such-gate", claimedEnforced = true)))
        val snapshot = DocsTruthSnapshot(
            adrRecords = listOf(record),
            artifactExistence = emptyMap(),
            gateEnforcementStatus = emptyMap(),
        )

        assertThat(detector.detect(snapshot)).isEmpty()
    }
}
