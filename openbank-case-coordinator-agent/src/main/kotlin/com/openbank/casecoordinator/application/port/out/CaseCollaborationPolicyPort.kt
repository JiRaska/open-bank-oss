// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.application.port.out

data class CaseCollaborationPolicyQuery(
    val agentId: String,
    val capability: String,
    val caseClass: String,
    val deliveryMode: String,
)

data class CaseCollaborationPolicyDecision(
    val allow: Boolean,
    val reason: String,
    val decisionId: String = "",
    val rolloutId: String = "",
    val maxSignalsPerCase: Int = 0,
)

/** Dedicated ADR-0271 decision port; deliberately separate from the REST/MCP policy namespaces. */
interface CaseCollaborationPolicyPort {
    fun decide(query: CaseCollaborationPolicyQuery): CaseCollaborationPolicyDecision
}

class CaseCollaborationPolicyUnavailable(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
