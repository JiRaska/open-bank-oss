// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.devops.infrastructure.adapter

import com.openbank.devops.application.port.out.RemediationProposalPort
import com.openbank.devops.domain.model.DevOpsFinding
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * GitHub App adapter for opening durable-remediation proposal pull requests.
 *
 * Uses a GitHub App installation token under the `write_proposal` charter tier (agents.yaml):
 * the agent may OPEN a PR but the charter denies `gh.pr.merge`/`gh.pr.approve` — a human merges
 * after HITL review (ADR-0031 D4, segregation of duties). Full implementation is the ADR-0119
 * follow-up; this stub returns a placeholder URL to keep the workflow structurally complete.
 */
@ApplicationScoped
class RemediationProposalAdapter : RemediationProposalPort {

    private val log = Logger.getLogger(RemediationProposalAdapter::class.java)

    companion object {
        private const val PR_ID_PREFIX_LEN = 8
    }

    override suspend fun openProposalPr(finding: DevOpsFinding, remediation: String): String {
        log.infof(
            "GitHub PR proposal requested for finding %s detector=%s kind=%s — stub",
            finding.id,
            finding.detector,
            finding.remediationKind,
        )
        // TODO(ADR-0119 follow-up): create branch + PR via GitHub App installation token.
        return "https://github.com/JiRaska/open-bank/pulls/pending-devops-${finding.id.take(PR_ID_PREFIX_LEN)}"
    }
}
