// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.domain.model

import java.time.Duration
import java.time.Instant

/** Selects one measured gate within a verified single artifact, never a pipeline-wide winner. */
object CpuOptimizationAdvisoryPolicy {
    fun select(profile: CiGateProfileEvidence, now: Instant): CiGateObservation? {
        val provenance = profile.provenance
        if (provenance == null || !trusted(profile, provenance, now)) return null
        return profile.gates.asSequence()
            .filter(::eligible)
            .minWithOrNull(compareByDescending<CiGateObservation> { it.cpuSeconds }.thenBy { it.id })
    }

    private fun trusted(profile: CiGateProfileEvidence, proof: CiGateProfileProvenance, now: Instant): Boolean =
        profile.state == DeliveryEvidenceState.PARTIAL &&
            profile.reason == "SINGLE_ARTIFACT" &&
            identity(profile.repository, proof) &&
            fresh(profile.observedAt, proof.artifactCreatedAt, now) &&
            validGates(profile.gates)

    private fun identity(repository: String, proof: CiGateProfileProvenance): Boolean =
        REPOSITORY.matches(repository) &&
            repository.substringAfter('/') !in setOf(".", "..") &&
            SHA.matches(proof.sourceSha) &&
            DIGEST.matches(proof.artifactDigest) &&
            proof.runId > 0 &&
            proof.runAttempt > 0 &&
            proof.artifactId > 0 &&
            ARTIFACT.matchEntire(proof.artifactName)?.groupValues?.get(1) == proof.runAttempt.toString()

    private fun fresh(observedAt: Instant, createdAt: Instant, now: Instant): Boolean = !createdAt.isAfter(now) &&
        Duration.between(createdAt, now) <= MAX_AGE &&
        !observedAt.isBefore(createdAt) &&
        !observedAt.isAfter(now)

    private fun validGates(gates: List<CiGateObservation>): Boolean = gates.all { GATE_ID.matches(it.id) } &&
        gates.map { it.id }.distinct().size == gates.size &&
        gates.all { it.cpuSeconds.isFinite() && it.cpuSeconds >= 0 }

    private fun eligible(gate: CiGateObservation): Boolean {
        val subjects = gate.subjects
        val floor = gate.minSubjects
        return gate.cpuSeconds > 0 &&
            gate.status == "ok" &&
            gate.mode == "enforced" &&
            gate.failureKind == null &&
            gate.selftestDeclared &&
            gate.selftestPassed == true &&
            !gate.selftestSkipped &&
            subjects != null &&
            floor != null &&
            floor >= 1 &&
            subjects >= floor
    }

    private const val FRESHNESS_HOURS = 24L
    private val MAX_AGE = Duration.ofHours(FRESHNESS_HOURS)
    private val SHA = Regex("[0-9a-f]{40}")
    private val DIGEST = Regex("sha256:[0-9a-f]{64}")
    private val REPOSITORY = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?/[A-Za-z0-9_.-]{1,100}")
    private val ARTIFACT = Regex("gate-results-[a-z0-9_-]{1,80}-a([1-9][0-9]*)")
    private val GATE_ID = Regex("[a-z0-9][a-z0-9_-]{0,79}")
}
