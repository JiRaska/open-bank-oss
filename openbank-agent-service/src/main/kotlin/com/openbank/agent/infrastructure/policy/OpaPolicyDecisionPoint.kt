// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.agent.infrastructure.policy

import com.openbank.agent.application.PolicyDecisionPoint
import com.openbank.agent.domain.policy.PolicyDecision
import com.openbank.agent.domain.policy.PolicyQuery
import io.quarkus.arc.properties.IfBuildProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger

/**
 * OPA-backed PDP (ADR-0031 D2), active when `agent.policy.opa.enabled=true`. Queries the
 * sidecar's `decision` rule. Fail-closed: any transport error, missing result, or malformed
 * response is treated as DENY, never as an implicit allow.
 */
@ApplicationScoped
@IfBuildProperty(name = "agent.policy.opa.enabled", stringValue = "true")
class OpaPolicyDecisionPoint : PolicyDecisionPoint {

    @Inject
    @RestClient
    lateinit var opa: OpaClient

    private val log = Logger.getLogger(OpaPolicyDecisionPoint::class.java)

    override fun evaluate(query: PolicyQuery): PolicyDecision {
        val input = buildMap<String, Any?> {
            put("agent", query.agent)
            put("tool", query.tool)
            put("resource", query.resource)
            put("plane", query.plane)
            put("attributes", query.attributes)
        }
        return try {
            val result = opa.decision(OpaRequest(input)).result
                ?: return denied(query, "OPA returned no decision")
            PolicyDecision(
                allow = result.allow,
                agent = query.agent,
                tool = query.tool,
                resource = query.resource,
                reason = result.reason ?: if (result.allow) "allowed by policy" else "denied by policy",
            )
        } catch (e: Exception) {
            log.warnf(e, "OPA decision query failed for tool=%s agent=%s; failing closed", query.tool, query.agent)
            // pdpError=true signals AgentPolicyGate that the PDP itself errored (not a policy DENY),
            // so BLOCK mode can safely fall back to advisory without being exploited by rules whose
            // reason text happens to contain "unavailable".
            PolicyDecision(
                allow = false,
                agent = query.agent,
                tool = query.tool,
                resource = query.resource,
                reason = "OPA unreachable, fail-closed: ${e.message}",
                pdpError = true,
            )
        }
    }

    private fun denied(query: PolicyQuery, reason: String) = PolicyDecision(
        allow = false,
        agent = query.agent,
        tool = query.tool,
        resource = query.resource,
        reason = reason,
    )
}
