// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.devops.domain.model

import java.math.BigDecimal
import java.time.Instant

/**
 * DevOps/SRE detectors (ADR-0119). The DevOps agent is to the SSDLC + DORA what the
 * finops-agent (ADR-0112) is to cloud cost: it watches the delivery pipeline, detects
 * degradation, diagnoses the root cause with an LLM, and proposes a DURABLE fix
 * (a code/IaC PR, a runbook update, a tracking ticket) through the HITL queue.
 *
 * D3_RUNNER_CAPACITY is the detector that would have caught the 2026-06-27 incident
 * where the openbank-batch label was added live via the GitHub API (not durable in
 * reregister-runner.sh) — 0 online batch runners, batch jobs queued indefinitely.
 */
enum class DetectorId {
    D1_CI_PIPELINE_HEALTH, // repeated build/lint failures, rising build duration, flaky tests
    D2_DORA_REGRESSION, // a DORA metric degrades vs. its rolling baseline
    D3_RUNNER_CAPACITY, // runner pool starved: queue pressure high / 0 online runners for a pool
    D4_DEPLOY_HEALTH, // rollout aborted, deploy failed, can-i-deploy blocked, stale image
    D5_SSDLC_HYGIENE, // coverage below floor, openapi drift, missing version bump, missing threat model
    D6_INCIDENT_RECURRENCE, // the same alert/incident recurs — learning loop: propose a permanent guard
}

enum class FindingSeverity { WARNING, CRITICAL }

enum class FindingStatus { OPEN, DIAGNOSED, PROPOSED, APPROVED, REJECTED, RESOLVED }

/** The four DORA metrics (ADR-0061). A finding records which one it puts at risk. */
enum class DoraMetric { DEPLOYMENT_FREQUENCY, LEAD_TIME_FOR_CHANGES, CHANGE_FAILURE_RATE, TIME_TO_RESTORE }

/** The kind of durable remediation the agent proposes — the whole point is a permanent fix, not a restart. */
enum class RemediationKind { PULL_REQUEST, RUNBOOK_UPDATE, TICKET, NONE }

/**
 * A single detected SSDLC/DORA problem. Mirrors the finops-agent CostAnomaly shape so the
 * admin-UI HITL surface and the Temporal workflow are structurally identical, but the
 * economic field (estimatedMonthlySavingUsd) is replaced by the DevOps-domain impact:
 * which DORA metric is at risk and what kind of durable remediation is proposed.
 */
data class DevOpsFinding(
    val id: String,
    val detector: DetectorId,
    val severity: FindingSeverity,
    val detectedAt: Instant,
    val title: String,
    val rawMetricValue: BigDecimal,
    val threshold: BigDecimal,
    val affectedResource: String,
    val doraMetricImpacted: DoraMetric? = null,
    val rootCause: String? = null,
    val remediationKind: RemediationKind = RemediationKind.NONE,
    val proposalPrUrl: String? = null,
    val proposedRemediation: String? = null,
    val status: FindingStatus = FindingStatus.OPEN,
    val diagnosedAt: Instant? = null,
    val proposedAt: Instant? = null,
)

data class DevOpsRunReport(
    val runId: String,
    val startedAt: Instant,
    val completedAt: Instant,
    val findingsDetected: List<DevOpsFinding>,
    val findingsProposed: Int,
    val doraMetricsAtRisk: Int,
    val tokensUsed: Int,
    val trigger: RunTrigger,
)

enum class RunTrigger { SCHEDULED, ALERT_WEBHOOK, OPERATOR_MANUAL }
