// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.docstruth.application.workflow

import com.openbank.docstruth.domain.model.AdrDeliveryStatus
import com.openbank.docstruth.domain.model.AdrRecord
import com.openbank.docstruth.domain.model.ArtifactExistence
import com.openbank.docstruth.domain.model.DocsTruthCheckType
import com.openbank.docstruth.domain.model.DocsTruthFinding
import com.openbank.docstruth.domain.model.DocsTruthSnapshot
import com.openbank.docstruth.domain.model.FindingSeverity
import com.openbank.docstruth.domain.model.FindingStatus
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Instant

@ApplicationScoped
open class DetectDriftActivityImpl : DetectDriftActivity {

    companion object {
        private val ENFORCED_VALUES = setOf("enforce", "active", "true")
        private val SHIPPED_STATUSES = setOf(AdrDeliveryStatus.SHIPPED, AdrDeliveryStatus.COMPLETE)
        private val PLANNED_STATUSES = setOf(AdrDeliveryStatus.PLANNED, AdrDeliveryStatus.PARTIAL)
    }

    override fun detect(snapshot: DocsTruthSnapshot): List<DocsTruthFinding> = buildList {
        for (adr in snapshot.adrRecords) {
            addAll(checkShippedArtifactMissing(adr, snapshot.artifactExistence))
            addAll(checkPlannedArtifactAlreadyShipped(adr, snapshot.artifactExistence))
            addAll(checkEnforcementStatusMismatch(adr, snapshot.gateEnforcementStatus))
        }
    }

    // Check 1 (ADR-0166): a Shipped/Complete ADR whose claimed artifacts cannot be found anywhere
    // else in the repo — a rotted, renamed, or never-actually-shipped artifact the ADR was never
    // revisited for.
    private fun checkShippedArtifactMissing(
        adr: AdrRecord,
        existence: Map<String, ArtifactExistence>,
    ): List<DocsTruthFinding> {
        if (adr.deliveryStatus !in SHIPPED_STATUSES || adr.claimedArtifacts.isEmpty()) return emptyList()
        val missing = adr.claimedArtifacts.map { it.name }.filter { existence[it]?.exists == false }
        if (missing.isEmpty()) return emptyList()
        val allMissing = missing.size == adr.claimedArtifacts.size
        val severity = if (allMissing) FindingSeverity.CRITICAL else FindingSeverity.WARNING
        val title = if (allMissing) {
            "${adr.id} claims Delivery-Status: ${adr.deliveryStatus} but NONE of its claimed " +
                "artifacts (${missing.joinToString()}) were found in the repo"
        } else {
            "${adr.id} claims Delivery-Status: ${adr.deliveryStatus} but ${missing.joinToString()} " +
                "could not be found in the repo (other claimed artifacts were found)"
        }
        return listOf(
            newFinding(
                adr = adr,
                checkType = DocsTruthCheckType.SHIPPED_ARTIFACT_MISSING,
                severity = severity,
                title = title,
                rawMetricValue = BigDecimal.valueOf(missing.size.toLong()),
                threshold = BigDecimal.ZERO,
            ),
        )
    }

    // Check 2 (ADR-0166): the ADR-0139/ADR-0140 case — an ADR explicitly claims an artifact does
    // NOT exist yet ("not yet implemented"-style prose next to the backtick reference), but a
    // repo-wide scan finds it anyway.
    private fun checkPlannedArtifactAlreadyShipped(
        adr: AdrRecord,
        existence: Map<String, ArtifactExistence>,
    ): List<DocsTruthFinding> {
        if (adr.deliveryStatus !in PLANNED_STATUSES) return emptyList()
        val notYetClaims = adr.claimedArtifacts.filter { it.claimedNotYetBuilt }
        return notYetClaims.mapNotNull { claim ->
            val found = existence[claim.name] ?: return@mapNotNull null
            if (!found.exists) return@mapNotNull null
            newFinding(
                adr = adr,
                checkType = DocsTruthCheckType.PLANNED_ARTIFACT_ALREADY_SHIPPED,
                severity = FindingSeverity.CRITICAL,
                title = "${adr.id} (Delivery-Status: ${adr.deliveryStatus}) claims `${claim.name}` does not " +
                    "exist yet, but it was found at ${found.matchedPaths.joinToString()} — the same shape as " +
                    "the ADR-0139/ADR-0140 feature-store incident (PR #944 -> #950)",
                rawMetricValue = BigDecimal.ONE,
                threshold = BigDecimal.ZERO,
            )
        }
    }

    // Check 3 (ADR-0166): an ADR/doc claims a gate is (or is not) enforced, but rules.yaml's own
    // gate-graduation `enforced:` flag (ADR-0144) says otherwise.
    private fun checkEnforcementStatusMismatch(
        adr: AdrRecord,
        gateEnforcementStatus: Map<String, String>,
    ): List<DocsTruthFinding> = adr.claimedEnforcements.mapNotNull { claim ->
        val actual = gateEnforcementStatus[claim.gateName] ?: return@mapNotNull null
        val actualEnforced = actual.lowercase() in ENFORCED_VALUES
        if (actualEnforced == claim.claimedEnforced) return@mapNotNull null
        val claimedText = if (claim.claimedEnforced) "enforced" else "advisory-only"
        newFinding(
            adr = adr,
            checkType = DocsTruthCheckType.ENFORCEMENT_STATUS_MISMATCH,
            severity = FindingSeverity.CRITICAL,
            title = "${adr.id} claims `${claim.gateName}` is $claimedText but rules.yaml says " +
                "enforced: $actual",
            rawMetricValue = BigDecimal.ONE,
            threshold = BigDecimal.ZERO,
        )
    }

    private fun newFinding(
        adr: AdrRecord,
        checkType: DocsTruthCheckType,
        severity: FindingSeverity,
        title: String,
        rawMetricValue: BigDecimal,
        threshold: BigDecimal,
    ) = DocsTruthFinding(
        id = Ids.newId().toString(),
        checkType = checkType,
        severity = severity,
        detectedAt = Instant.now(),
        title = title,
        component = adr.id,
        adrPath = adr.path,
        rawMetricValue = rawMetricValue,
        threshold = threshold,
        status = FindingStatus.OPEN,
    )
}
