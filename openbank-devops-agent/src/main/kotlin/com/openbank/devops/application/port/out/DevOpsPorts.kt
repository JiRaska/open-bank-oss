// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.devops.application.port.out

import com.openbank.devops.domain.model.DevOpsFinding
import java.time.Instant

interface PrometheusQueryPort {
    suspend fun queryInstant(promql: String): Double?
    suspend fun queryRange(promql: String, start: Instant, end: Instant, step: String): List<Pair<Instant, Double>>
}

interface LlmDiagnosisPort {
    /** Diagnose the root cause of a finding in natural language. */
    suspend fun diagnose(finding: DevOpsFinding, contextMetrics: Map<String, Double>): String

    /** Propose a durable remediation (a code/IaC diff, a runbook edit). Null = nothing safe to propose. */
    suspend fun proposeRemediation(finding: DevOpsFinding, diagnosis: String): String?
}

interface RemediationProposalPort {
    /** Open a proposal PR for HITL review. Returns the PR URL, or null if none was opened
     *  (e.g. the GitHub token is not seeded or the API call failed — the agent degrades gracefully). */
    suspend fun openProposalPr(finding: DevOpsFinding, remediation: String): String?
}

/**
 * SSDLC signals read straight from the GitHub REST API (no Prometheus exporter needed). Each returns
 * null when unavailable (token not seeded / API error), which keeps the dependent detector inert
 * rather than noisy.
 */
interface GitHubMetricsPort {
    /** Workflow-run failure rate over the most recent completed runs (0.0..1.0). */
    suspend fun ciFailureRate(): Double?

    /** Count of OPEN `fleet-health` issues — accumulated CI/SSDLC drift the nightly jobs file. */
    suspend fun openFleetHealthIssues(): Int?
}

interface FindingRepository {
    suspend fun save(finding: DevOpsFinding): DevOpsFinding
    suspend fun findActive(): List<DevOpsFinding>
    suspend fun findById(id: String): DevOpsFinding?
    suspend fun update(finding: DevOpsFinding): DevOpsFinding
}
