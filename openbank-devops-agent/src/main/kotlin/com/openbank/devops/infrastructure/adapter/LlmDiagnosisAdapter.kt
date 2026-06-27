// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.devops.infrastructure.adapter

import com.openbank.devops.application.port.out.LlmDiagnosisPort
import com.openbank.devops.domain.model.DevOpsFinding
import com.openbank.devops.infrastructure.config.DevOpsConfig
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * LiteLLM gateway adapter for AI-assisted SSDLC/DORA diagnosis and remediation proposals.
 *
 * Routes through the internal LiteLLM proxy (ADR-0031 D6 / ADR-0089) which selects the backend
 * model. The full /chat/completions wiring (structured prompt + retrieval over rules.yaml, ADRs,
 * runbooks and infra/) is the documented ADR-0119 follow-up; this stub keeps the Temporal workflow
 * structurally complete and returns a deterministic placeholder so the dashboard renders.
 */
@ApplicationScoped
class LlmDiagnosisAdapter(private val config: DevOpsConfig) : LlmDiagnosisPort {

    private val log = Logger.getLogger(LlmDiagnosisAdapter::class.java)

    override suspend fun diagnose(finding: DevOpsFinding, contextMetrics: Map<String, Double>): String {
        log.infof(
            "LLM diagnosis requested for finding %s detector=%s (gateway=%s) — stub",
            finding.id,
            finding.detector,
            config.llmGatewayUrl(),
        )
        // TODO(ADR-0119 follow-up): wire to LiteLLM /chat/completions with a structured prompt
        // grounded in rules.yaml + ADRs + runbooks. Read data is untrusted (prompt-injection guard).
        return "Automated diagnosis pending LiteLLM integration (ADR-0119 follow-up). " +
            "Finding: ${finding.title}. Affected: ${finding.affectedResource}. " +
            "DORA metric at risk: ${finding.doraMetricImpacted ?: "none"}."
    }

    override suspend fun proposeRemediation(finding: DevOpsFinding, diagnosis: String): String? {
        log.infof(
            "LLM remediation proposal requested for finding %s detector=%s kind=%s — stub",
            finding.id,
            finding.detector,
            finding.remediationKind,
        )
        // TODO(ADR-0119 follow-up): generate a code/IaC/runbook diff via LiteLLM + retrieval.
        return null
    }
}
