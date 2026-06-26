// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.agent.infrastructure.policy

import com.openbank.agent.application.PolicyDecisionPoint
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
