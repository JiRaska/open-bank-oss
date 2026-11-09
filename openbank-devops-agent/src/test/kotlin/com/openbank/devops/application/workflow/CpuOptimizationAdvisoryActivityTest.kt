// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.application.workflow

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.devops.application.port.out.FindingRepository
import com.openbank.devops.domain.model.CiGateObservation
import com.openbank.devops.domain.model.CiGateProfileEvidence
import com.openbank.devops.domain.model.CiGateProfileProvenance
import com.openbank.devops.domain.model.DeliveryEvidenceState
import com.openbank.devops.domain.model.DevOpsFinding
import com.openbank.devops.domain.model.FindingStatus
import com.openbank.devops.domain.model.RemediationKind
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class CpuOptimizationAdvisoryActivityTest {
    private val repository = mockk<FindingRepository>()
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val activity = object : CpuOptimizationAdvisoryActivityImpl(repository, mapper) {
        override fun <T> runOnVertxContext(block: suspend () -> T): T = runBlocking { block() }
    }

    private fun evidence(): CiGateProfileEvidence {
        val at = Instant.now().minusSeconds(30)
        return CiGateProfileEvidence(
            "example/repo",
            at,
            DeliveryEvidenceState.PARTIAL,
            "SINGLE_ARTIFACT",
            CiGateProfileProvenance("a".repeat(40), 11, 2, 12, "gate-results-lint-a2", "sha256:" + "b".repeat(64), at),
            listOf(
                CiGateObservation(
                    "lint", "lint", "enforced", "ok", null, 4.0, 3.0, 10.0,
                    1.0, true, true, false, 42, 1, false,
                ),
            ),
        )
    }

    @Test
    fun `advisory retains exact provenance and requires measurement without proposing a PR`() {
        coEvery { repository.saveAdvisoryIfAbsent(any()) } answers { firstArg() }
        val evidence = evidence()
        val out = requireNotNull(activity.assess(evidence))
        val saved = mapper.readTree(out.rootCause)
        assertThat(saved["provenance"]["sourceSha"].asText()).isEqualTo(evidence.provenance!!.sourceSha)
        assertThat(saved["provenance"]["runAttempt"].asInt()).isEqualTo(2)
        assertThat(saved["provenance"]["artifactDigest"].asText()).isEqualTo(evidence.provenance!!.artifactDigest)
        assertThat(saved["gate"]["cpuSeconds"].asDouble()).isEqualTo(3.0)
        assertThat(out.proposedRemediation).contains("alternating A/B", "actual whole gate", "Reject a net regression")
        assertThat(out.status).isEqualTo(FindingStatus.DIAGNOSED)
        assertThat(out.remediationKind).isEqualTo(RemediationKind.NONE)
        assertThat(out.doraMetricImpacted).isNull()
        assertThat(out.proposalPrUrl).isNull()
    }

    @Test
    fun `same immutable evidence has stable identity and returns the stored human lifecycle`() {
        val rows = mutableMapOf<String, DevOpsFinding>()
        coEvery { repository.saveAdvisoryIfAbsent(any()) } answers {
            val finding = firstArg<DevOpsFinding>()
            rows.getOrPut(finding.id) { finding }
        }
        val evidence = evidence()
        val first = requireNotNull(activity.assess(evidence))
        rows[first.id] = first.copy(status = FindingStatus.REJECTED, rootCause = "human disposition")
        val repeated = requireNotNull(activity.assess(evidence.copy(observedAt = evidence.observedAt.plusSeconds(1))))
        assertThat(repeated.id).isEqualTo(first.id)
        assertThat(repeated.status).isEqualTo(FindingStatus.REJECTED)
        assertThat(repeated.rootCause).isEqualTo("human disposition")
        val freshAttempt = requireNotNull(
            activity.assess(
                evidence.copy(
                    provenance = evidence.provenance!!.copy(
                        runAttempt = 3,
                        artifactName = "gate-results-lint-a3",
                    ),
                ),
            ),
        )
        assertThat(freshAttempt.id).isNotEqualTo(first.id)
    }

    @Test
    fun `unavailable profile does not write a finding`() {
        assertThat(activity.assess(evidence().copy(state = DeliveryEvidenceState.UNAVAILABLE))).isNull()
        coVerify(exactly = 0) { repository.saveAdvisoryIfAbsent(any()) }
    }
}
