// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finops.infrastructure.adapter

import com.openbank.finops.application.port.out.LlmDiagnosisPort
import com.openbank.finops.domain.model.CostAnomaly
import com.openbank.finops.infrastructure.config.FinOpsConfig
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * LiteLLM gateway adapter for AI-assisted anomaly diagnosis and IaC fix proposals.
 *
 * Communicates with the internal LiteLLM proxy (ADR-0089) which routes to the
 * configured backend model (meta/llama-3.1-70b-instruct in sandbox).
 * Full implementation tracked separately; this stub logs and returns a placeholder
 * to keep the workflow structurally complete for ADR-0112 P3.
 */
@ApplicationScoped
class LlmDiagnosisAdapter(private val config: FinOpsConfig) : LlmDiagnosisPort {

    private val log = Logger.getLogger(LlmDiagnosisAdapter::class.java)

    override suspend fun diagnose(anomaly: CostAnomaly, contextMetrics: Map<String, Double>): String {
        log.infof(
            "LLM diagnosis requested for anomaly %s detector=%s (gateway=%s) — stub",
            anomaly.id,
            anomaly.detector,
            config.llmGatewayUrl(),
        )
        // TODO(ADR-0112 P4): wire to LiteLLM /chat/completions with structured prompt
        return "Automated diagnosis pending LiteLLM integration (ADR-0112 P4). " +
            "Anomaly: ${anomaly.title}. Affected: ${anomaly.affectedResource}."
    }

    override suspend fun proposeIacFix(anomaly: CostAnomaly, diagnosis: String): String? {
        log.infof(
            "LLM IaC fix proposal requested for anomaly %s detector=%s — stub",
            anomaly.id,
            anomaly.detector,
        )
        // TODO(ADR-0112 P4): generate OpenTofu diff via LiteLLM + retrieval from infra/
        return null
    }
}
