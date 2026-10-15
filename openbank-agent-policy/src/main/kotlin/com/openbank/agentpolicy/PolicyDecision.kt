// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agentpolicy

/**
 * One policy evaluation, parameterised by the caller. [tool], [resource] and [tier] travel to
 * OPA as `input.tool` / `input.resource` / `input.tier`; the charter verifier re-validates the
 * ([tier], [resource]) pair against the agent's packaged charter. Callers that want the
 * [AgentPolicyConfig]'s own binding pass [AgentPolicyConfig.defaultInput].
 */
data class PolicyDecisionInput(val tool: String, val resource: String, val tier: String)

/**
 * The outcome of a [PolicyGate] evaluation. A [Denied] MUST veto the entire write operation the
 * gate fronts — not just one HTTP call — and the consuming service records the disposition in
 * its audit trail either way (phase-3 D5 contract; audit publishing stays in the service, this
 * module only decides).
 */
sealed interface PolicyDecision {
    data object Allowed : PolicyDecision

    data class Denied(val reason: String) : PolicyDecision
}
