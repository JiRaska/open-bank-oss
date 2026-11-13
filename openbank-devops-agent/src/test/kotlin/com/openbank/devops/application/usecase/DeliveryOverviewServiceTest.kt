// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.application.usecase

import com.openbank.devops.application.port.out.DeliveryEvidencePort
import com.openbank.devops.domain.model.DeliveryEvidence
import com.openbank.devops.domain.model.DeliveryEvidenceSource
import com.openbank.devops.domain.model.DeliveryEvidenceState
import com.openbank.devops.domain.model.DeliveryPullRequest
import com.openbank.devops.domain.model.DeliveryWorkflowRun
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class DeliveryOverviewServiceTest {
    private val end = Instant.parse("2026-01-02T00:00:00Z")
    private val start = end.minus(Duration.ofDays(1))
    private val port = mockk<DeliveryEvidencePort>()
    private val service = DeliveryOverviewService(port, Clock.fixed(end, ZoneOffset.UTC))

    private fun run(id: Long, created: Instant = start, path: String = ".github/workflows/ci.yml") =
        DeliveryWorkflowRun(id, path, "completed", "success", created, created.plusSeconds(id), 1)

    @Test
    fun `counts only cohort members and never claims a verified deployment`() {
        val runs = listOf(
            run(1),
            run(2, end),
            run(3, start.minusSeconds(1)),
            run(4, path = ".github/workflows/auto-deploy.yml"),
            run(5, path = ".github/workflows/other.yml"),
        )
        val pulls = listOf(
            DeliveryPullRequest(1, start.minusSeconds(5), start, start),
            DeliveryPullRequest(2, start, end, end),
            DeliveryPullRequest(3, start, end, null),
        )
        val result = service.summarize(
            DeliveryEvidence(
                "example/repo",
                DeliveryEvidenceSource(DeliveryEvidenceState.COMPLETE, runs),
                DeliveryEvidenceSource(DeliveryEvidenceState.COMPLETE, pulls),
            ),
            start,
            end,
        )
        assertThat(result.workflows.map { it.observedRuns }).containsExactly(1, 1, 1)
        assertThat(result.mergedPrsPerDay).isEqualTo(1)
        assertThat(result.mode).isEqualTo("SHADOW")
        assertThat(result.deploymentVerification).isEqualTo("NOT_COLLECTED")
        assertThat(result.verifiedDeploymentsPerDay).isNull()
    }

    @Test
    fun `unavailable means null rather than a healthy zero and partial counts are only observations`() {
        val missing = DeliveryEvidence(
            "example/repo",
            DeliveryEvidenceSource(DeliveryEvidenceState.UNAVAILABLE),
            DeliveryEvidenceSource(DeliveryEvidenceState.UNAVAILABLE),
        )
        val unavailable = service.summarize(missing, start, end)
        assertThat(unavailable.workflows).allSatisfy { assertThat(it.observedRuns).isNull() }
        assertThat(unavailable.mergedPrsPerDay).isNull()
        assertThat(unavailable.observedMergedPrs).isNull()
        val partial = service.summarize(
            missing.copy(
                pullRequests = DeliveryEvidenceSource(
                    DeliveryEvidenceState.PARTIAL,
                    listOf(DeliveryPullRequest(1, start, end, start)),
                ),
            ),
            start,
            end,
        )
        assertThat(partial.observedMergedPrs).isEqualTo(1)
        assertThat(partial.mergedPrsPerDay).isNull()
    }

    @Test
    fun `reports workflow states and nearest rank delays without counting reruns twice`() {
        val runs = listOf(
            run(1),
            run(2).copy(conclusion = "failure", attempt = 3),
            run(3).copy(conclusion = "cancelled"),
            run(4).copy(status = "queued", conclusion = null, startedAt = null),
            run(5).copy(status = "in_progress", conclusion = null),
            run(6).copy(conclusion = "neutral"),
        )
        val result = service.summarize(
            DeliveryEvidence(
                "example/repo",
                DeliveryEvidenceSource(DeliveryEvidenceState.COMPLETE, runs),
                DeliveryEvidenceSource(DeliveryEvidenceState.COMPLETE),
            ),
            start,
            end,
        ).workflows.first()
        assertThat(result.observedRuns).isEqualTo(6)
        assertThat(result.completed).isEqualTo(4)
        assertThat(result.successful).isEqualTo(1)
        assertThat(result.unsuccessful).isEqualTo(1)
        assertThat(result.cancelled).isEqualTo(1)
        assertThat(result.waiting).isEqualTo(1)
        assertThat(result.running).isEqualTo(1)
        assertThat(result.runsWithReruns).isEqualTo(1)
        assertThat(result.startDelaySamples).isEqualTo(5)
        assertThat(result.startDelayP50Seconds).isEqualTo(3)
        assertThat(result.startDelayP95Seconds).isEqualTo(6)
    }

    @Test
    fun `single flight cache keeps observation time and bounds repeated API reads`(): Unit = runBlocking {
        val data = DeliveryEvidence(
            "example/repo",
            DeliveryEvidenceSource(DeliveryEvidenceState.COMPLETE),
            DeliveryEvidenceSource(DeliveryEvidenceState.COMPLETE),
        )
        coEvery { port.collect(start, end) } returns data
        val responses = coroutineScope { (1..10).map { async { service.getOverview() } }.awaitAll() }
        assertThat(responses.map { it.observedAt }).containsOnly(end)
        coVerify(exactly = 1) { port.collect(any(), any()) }
    }
}
