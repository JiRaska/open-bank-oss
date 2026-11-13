// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.application.usecase

import com.openbank.devops.application.port.incoming.GetDeliveryOverviewUseCase
import com.openbank.devops.application.port.out.DeliveryEvidencePort
import com.openbank.devops.domain.model.DeliveryEvidence
import com.openbank.devops.domain.model.DeliveryEvidenceState
import com.openbank.devops.domain.model.DeliveryOverview
import com.openbank.devops.domain.model.DeliveryWorkflowRun
import com.openbank.devops.domain.model.DeliveryWorkflowSummary
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil

@ApplicationScoped
class DeliveryOverviewService(private val evidence: DeliveryEvidencePort, private val clock: Clock) :
    GetDeliveryOverviewUseCase {
    private val lock = Mutex()
    private var cached: DeliveryOverview? = null

    override suspend fun getOverview(): DeliveryOverview = lock.withLock {
        val now = Instant.now(clock)
        cached?.takeIf {
            !now.isBefore(it.observedAt) && now.isBefore(it.observedAt.plus(CACHE_TTL))
        }?.let { return it }
        val start = now.minus(WINDOW)
        summarize(evidence.collect(start, now), start, now).also { cached = it }
    }

    internal fun summarize(data: DeliveryEvidence, start: Instant, end: Instant): DeliveryOverview {
        val available = data.workflows.state != DeliveryEvidenceState.UNAVAILABLE
        val runs = data.workflows.records.filter {
            available && !it.createdAt.isBefore(start) && it.createdAt.isBefore(end)
        }
        val groups = listOf("CI", "CD_AUTOMATION", "OTHER").map { kind ->
            summary(kind, runs.filter { family(it.path) == kind }, available)
        }
        val merged = data.pullRequests.records.count { pr ->
            pr.mergedAt?.let { !it.isBefore(start) && it.isBefore(end) } == true
        }
        return DeliveryOverview(
            observedAt = end,
            windowStart = start,
            windowEnd = end,
            repository = data.repository,
            workflowEvidence = data.workflows.state,
            workflowReason = data.workflows.reason,
            workflowPagesRead = data.workflows.pagesRead,
            workflows = groups,
            pullRequestEvidence = data.pullRequests.state,
            pullRequestReason = data.pullRequests.reason,
            pullRequestPagesRead = data.pullRequests.pagesRead,
            observedMergedPrs = merged.takeIf { data.pullRequests.state != DeliveryEvidenceState.UNAVAILABLE },
            mergedPrsPerDay = merged.takeIf { data.pullRequests.state == DeliveryEvidenceState.COMPLETE },
        )
    }

    private fun summary(family: String, runs: List<DeliveryWorkflowRun>, available: Boolean): DeliveryWorkflowSummary {
        fun count(predicate: (DeliveryWorkflowRun) -> Boolean) = runs.count(predicate).takeIf { available }
        val delays = runs.mapNotNull { run ->
            run.startedAt?.takeUnless {
                it.isBefore(run.createdAt)
            }?.let { Duration.between(run.createdAt, it).seconds }
        }.sorted()
        fun percentile(percent: Int): Long? = delays.takeIf { it.isNotEmpty() }?.let {
            it[ceil(percent.toDouble() * it.size / PERCENT).toInt() - 1]
        }
        return DeliveryWorkflowSummary(
            family, runs.size.takeIf { available }, count { it.status == "completed" },
            count { it.conclusion == "success" && it.status == "completed" },
            count { it.conclusion in FAILURES && it.status == "completed" }, count { it.conclusion == "cancelled" },
            count { it.status in WAITING }, count { it.status == "in_progress" }, count { it.attempt > 1 },
            delays.size.takeIf { available }, percentile(MEDIAN), percentile(P95),
        )
    }

    private fun family(path: String): String = when (path.substringBefore('@')) {
        ".github/workflows/ci.yml", ".github/workflows/services-ci.yml", ".github/workflows/security.yml" -> "CI"
        ".github/workflows/auto-deploy.yml", ".github/workflows/admin-ui-deploy.yml",
        ".github/workflows/deploy-window-flush.yml",
        -> "CD_AUTOMATION"
        else -> "OTHER"
    }

    private companion object {
        val WINDOW: Duration = Duration.ofDays(1)
        val CACHE_TTL: Duration = Duration.ofMinutes(5)
        val WAITING = setOf("queued", "waiting", "pending", "requested")
        val FAILURES = setOf("failure", "timed_out", "startup_failure", "action_required", "stale")
        const val PERCENT = 100
        const val MEDIAN = 50
        const val P95 = 95
    }
}
