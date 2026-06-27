// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finops.infrastructure.adapter

import com.openbank.finops.application.port.out.GitHubProposalPort
import com.openbank.finops.domain.model.CostAnomaly
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * GitHub App adapter for opening IaC fix proposal pull requests.
 *
 * Uses a GitHub App installation token (ADR-0112 P3 gate: HITL approval).
 * Full implementation tracked separately; this stub returns a placeholder URL
 * to keep the workflow structurally complete for ADR-0112 P3.
 */
@ApplicationScoped
class GitHubProposalAdapter : GitHubProposalPort {

    private val log = Logger.getLogger(GitHubProposalAdapter::class.java)

    companion object {
        private const val PR_ID_PREFIX_LEN = 8
    }

    override suspend fun openProposalPr(anomaly: CostAnomaly, iacDiff: String): String {
        log.infof(
            "GitHub PR proposal requested for anomaly %s detector=%s — stub",
            anomaly.id,
            anomaly.detector,
        )
        // TODO(ADR-0112 P4): create branch + PR via GitHub App installation token
        return "https://github.com/openbank/openbank/pulls/pending-finops-${anomaly.id.take(PR_ID_PREFIX_LEN)}"
    }
}
