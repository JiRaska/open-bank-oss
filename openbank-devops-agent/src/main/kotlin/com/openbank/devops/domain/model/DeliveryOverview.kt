// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.domain.model

import java.time.Instant

enum class DeliveryEvidenceState { COMPLETE, PARTIAL, UNAVAILABLE }

data class DeliveryEvidenceSource<T>(
    val state: DeliveryEvidenceState,
    val records: List<T> = emptyList(),
    val pagesRead: Int = 0,
    val reason: String? = null,
)

data class DeliveryWorkflowRun(
    val id: Long,
    val path: String,
    val status: String,
    val conclusion: String?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val attempt: Int,
)

data class DeliveryPullRequest(val number: Int, val createdAt: Instant, val updatedAt: Instant, val mergedAt: Instant?)

data class DeliveryEvidence(
    val repository: String,
    val workflows: DeliveryEvidenceSource<DeliveryWorkflowRun>,
    val pullRequests: DeliveryEvidenceSource<DeliveryPullRequest>,
)

data class DeliveryWorkflowSummary(
    val family: String,
    val observedRuns: Int?,
    val completed: Int?,
    val successful: Int?,
    val unsuccessful: Int?,
    val cancelled: Int?,
    val waiting: Int?,
    val running: Int?,
    val runsWithReruns: Int?,
    val startDelaySamples: Int?,
    val startDelayP50Seconds: Long?,
    val startDelayP95Seconds: Long?,
)

data class DeliveryOverview(
    val observedAt: Instant,
    val windowStart: Instant,
    val windowEnd: Instant,
    val repository: String,
    val workflowEvidence: DeliveryEvidenceState,
    val workflowReason: String?,
    val workflowPagesRead: Int,
    val workflows: List<DeliveryWorkflowSummary>,
    val pullRequestEvidence: DeliveryEvidenceState,
    val pullRequestReason: String?,
    val pullRequestPagesRead: Int,
    val observedMergedPrs: Int?,
    val mergedPrsPerDay: Int?,
    val mode: String = "SHADOW",
    val deploymentVerification: String = "NOT_COLLECTED",
    val verifiedDeploymentsPerDay: Int? = null,
)
