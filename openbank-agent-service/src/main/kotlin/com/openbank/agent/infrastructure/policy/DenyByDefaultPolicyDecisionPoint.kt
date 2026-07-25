// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.policy

import com.openbank.agent.application.port.out.PolicyDecisionPoint
import com.openbank.agent.domain.policy.PolicyDecision
import com.openbank.agent.domain.policy.PolicyQuery
import io.quarkus.arc.DefaultBean
import jakarta.enterprise.context.ApplicationScoped

/**
 * The safe fallback PDP, active whenever the OPA sidecar is not wired in
 * (`agent.policy.opa.enabled` unset/false). It denies everything, so the gate is real and
 * safe from day one: with no policy engine, no agent is authorized. Used in phase 1 until the
 * OPA bundle (agents.yaml + agents.rego + rules.yaml) is deployed alongside the sidecar
 * (ADR-0018 / ADR-0031 D2).
 */
@ApplicationScoped
@DefaultBean
class DenyByDefaultPolicyDecisionPoint : PolicyDecisionPoint {

    override fun evaluate(query: PolicyQuery): PolicyDecision = PolicyDecision(
        allow = false,
        agent = query.agent,
        tool = query.tool,
        resource = query.resource,
        reason = "deny-by-default: no policy engine configured (ADR-0031 D2)",
    )
}
