// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class CpuOptimizationAdvisoryPolicyTest {
    private val now = Instant.parse("2026-11-08T12:00:00Z")
    private val proof = CiGateProfileProvenance(
        sourceSha = "a".repeat(40),
        runId = 10,
        runAttempt = 2,
        artifactId = 20,
        artifactName = "gate-results-lint-a2",
        artifactDigest = "sha256:" + "b".repeat(64),
        artifactCreatedAt = now.minusSeconds(60),
    )
    private val gate = CiGateObservation(
        id = "slow-gate",
        group = "lint",
        mode = "enforced",
        status = "ok",
        failureKind = null,
        wallSeconds = 2.0,
        cpuSeconds = 8.0,
        budgetSeconds = null,
        selftestSeconds = 1.0,
        selftestDeclared = true,
        selftestPassed = true,
        selftestSkipped = false,
        subjects = 3,
        minSubjects = 2,
        wallOverBudget = false,
    )
    private val profile = CiGateProfileEvidence(
        repository = "example/repo",
        observedAt = now.minusSeconds(10),
        state = DeliveryEvidenceState.PARTIAL,
        reason = "SINGLE_ARTIFACT",
        provenance = proof,
        gates = listOf(gate),
    )

    @Test
    fun `selects the largest eligible CPU cost only within the supplied single artifact`() {
        val highest = gate.copy(id = "highest-gate", cpuSeconds = 12.0)
        val failed = gate.copy(id = "failed-gate", cpuSeconds = 100.0, status = "failed", failureKind = "content")
        val result = CpuOptimizationAdvisoryPolicy.select(profile.copy(gates = listOf(failed, gate, highest)), now)
        assertThat(result).isEqualTo(highest)
        assertThat(profile.state).isEqualTo(DeliveryEvidenceState.PARTIAL)
    }

    @Test
    fun `ties choose the lexical smallest id independently of input order`() {
        val first = gate.copy(id = "a-gate")
        val second = gate.copy(id = "b-gate")
        for (order in listOf(listOf(first, second), listOf(second, first))) {
            assertThat(CpuOptimizationAdvisoryPolicy.select(profile.copy(gates = order), now)).isEqualTo(first)
        }
    }

    @Test
    fun `missing untrusted and non-single-artifact evidence cannot create an advisory`() {
        val invalid = listOf(
            profile.copy(provenance = null),
            profile.copy(state = DeliveryEvidenceState.UNAVAILABLE),
            profile.copy(state = DeliveryEvidenceState.COMPLETE),
            profile.copy(reason = null),
            profile.copy(reason = "PAGE_LIMIT"),
            profile.copy(repository = ""),
            profile.copy(repository = "example/repo/other"),
            profile.copy(repository = "example/.."),
            profile.copy(repository = "example/repo;instruction"),
        )
        for (value in invalid) assertThat(CpuOptimizationAdvisoryPolicy.select(value, now)).isNull()
    }

    @Test
    fun `source run attempt archive identity and algorithm must be exact`() {
        val invalid = listOf(
            proof.copy(sourceSha = "a".repeat(39)),
            proof.copy(sourceSha = "A".repeat(40)),
            proof.copy(runId = 0),
            proof.copy(runAttempt = 0),
            proof.copy(artifactId = -1),
            proof.copy(artifactName = "gate-results-lint-a1"),
            proof.copy(artifactName = "gate-results-lint-a02"),
            proof.copy(artifactName = "../../lint-a2"),
            proof.copy(artifactDigest = "sha256:" + "b".repeat(63)),
            proof.copy(artifactDigest = "md5:" + "b".repeat(64)),
        )
        for (value in invalid) {
            assertThat(CpuOptimizationAdvisoryPolicy.select(profile.copy(provenance = value), now)).isNull()
        }
    }

    @Test
    fun `age and observation ordering reject stale future or impossible evidence`() {
        val invalid = listOf(
            profile.copy(provenance = proof.copy(artifactCreatedAt = now.minusSeconds(86401))),
            profile.copy(provenance = proof.copy(artifactCreatedAt = now.plusSeconds(1))),
            profile.copy(observedAt = now.plusSeconds(1)),
            profile.copy(observedAt = proof.artifactCreatedAt.minusSeconds(1)),
        )
        for (value in invalid) assertThat(CpuOptimizationAdvisoryPolicy.select(value, now)).isNull()
        val boundary = profile.copy(provenance = proof.copy(artifactCreatedAt = now.minusSeconds(86400)))
        assertThat(CpuOptimizationAdvisoryPolicy.select(boundary, now)).isEqualTo(gate)
    }

    @Test
    fun `all gate ids must be unique safe and bounded even for excluded gates`() {
        val invalid = listOf(
            listOf(gate, gate.copy(cpuSeconds = 2.0)),
            listOf(gate.copy(id = "")),
            listOf(gate.copy(id = "a".repeat(81))),
            listOf(gate, gate.copy(id = "../other", status = "skipped", cpuSeconds = 0.0)),
            listOf(gate.copy(id = "gate\nother")),
        )
        for (rows in invalid) {
            assertThat(CpuOptimizationAdvisoryPolicy.select(profile.copy(gates = rows), now)).isNull()
        }
        assertThat(
            CpuOptimizationAdvisoryPolicy.select(profile.copy(gates = listOf(gate.copy(id = "a".repeat(80)))), now),
        )
            .isNotNull()
    }

    @Test
    fun `nonfinite or negative CPU rejects malformed evidence and zero CPU is not a candidate`() {
        for (cpu in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)) {
            val malformed = gate.copy(id = "malformed-gate", cpuSeconds = cpu)
            assertThat(
                CpuOptimizationAdvisoryPolicy.select(profile.copy(gates = listOf(gate, malformed)), now),
            ).isNull()
        }
        assertThat(CpuOptimizationAdvisoryPolicy.select(profile.copy(gates = listOf(gate.copy(cpuSeconds = 0.0))), now))
            .isNull()
        assertThat(CpuOptimizationAdvisoryPolicy.select(profile.copy(gates = emptyList()), now)).isNull()
    }

    @Test
    fun `failure advisory mode and every missing selftest proof disqualify that gate`() {
        val invalid = listOf(
            gate.copy(status = "warned"),
            gate.copy(status = "unfalsified"),
            gate.copy(mode = "advisory"),
            gate.copy(failureKind = "budget"),
            gate.copy(selftestDeclared = false),
            gate.copy(selftestPassed = null),
            gate.copy(selftestPassed = false),
            gate.copy(selftestSkipped = true),
        )
        for (value in invalid) {
            assertThat(CpuOptimizationAdvisoryPolicy.select(profile.copy(gates = listOf(value)), now)).isNull()
        }
    }

    @Test
    fun `subject count and positive floor must both be present and proven`() {
        val invalid = listOf(
            gate.copy(subjects = null),
            gate.copy(minSubjects = null),
            gate.copy(minSubjects = 0),
            gate.copy(minSubjects = -1),
            gate.copy(subjects = 1, minSubjects = 2),
        )
        for (value in invalid) {
            assertThat(CpuOptimizationAdvisoryPolicy.select(profile.copy(gates = listOf(value)), now)).isNull()
        }
        assertThat(CpuOptimizationAdvisoryPolicy.select(profile.copy(gates = listOf(gate.copy(subjects = 2))), now))
            .isNotNull()
    }
}
