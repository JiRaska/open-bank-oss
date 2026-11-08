// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.domain.model

import java.time.Instant

/** Verified bytes from one artifact are explicitly partial coverage of the pipeline. */
data class CiGateProfileEvidence(
    val repository: String,
    val observedAt: Instant,
    val state: DeliveryEvidenceState,
    val reason: String? = null,
    val provenance: CiGateProfileProvenance? = null,
    val gates: List<CiGateObservation> = emptyList(),
)

data class CiGateProfileProvenance(
    val sourceSha: String,
    val runId: Long,
    val runAttempt: Int,
    val artifactId: Long,
    val artifactName: String,
    val artifactDigest: String,
    val artifactCreatedAt: Instant,
)

/** Observed costs and verdicts, never an estimated saving or permission to skip a check. */
data class CiGateObservation(
    val id: String,
    val group: String?,
    val mode: String,
    val status: String,
    val failureKind: String?,
    val wallSeconds: Double,
    val cpuSeconds: Double,
    val budgetSeconds: Double?,
    val selftestSeconds: Double,
    val selftestDeclared: Boolean,
    val selftestPassed: Boolean?,
    val selftestSkipped: Boolean,
    val subjects: Int?,
    val minSubjects: Int?,
    val wallOverBudget: Boolean,
)
