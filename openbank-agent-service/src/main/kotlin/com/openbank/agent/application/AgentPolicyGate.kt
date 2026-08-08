// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application

import com.openbank.agent.application.port.out.PolicyDecisionPoint
import com.openbank.agent.domain.policy.AgentIdentity
import com.openbank.agent.domain.policy.EnforcementMode
import com.openbank.agent.domain.policy.GateOutcome
import com.openbank.agent.domain.policy.PolicyDecision
import com.openbank.agent.domain.policy.PolicyQuery
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * The MCP policy enforcement point (ADR-0031 D2). Every `tools/call` passes through here:
 * it asks the [PolicyDecisionPoint], records an AI-attributed [AuditEvent] for the decision
 * (ADR-0031 D5), and reports whether the call may proceed under the active [EnforcementMode].
 *
 * Phase 1 (ADR-0031 D9) runs ADVISORY + deny-by-default: a missing identity or absent policy
 * engine yields DENY, every decision is audited, but the call is not blocked yet — so the audit
 * trail shows what *would* be denied before enforcement is switched on.
 */
@ApplicationScoped
class AgentPolicyGate {

    @Inject
    lateinit var pdp: PolicyDecisionPoint

    @Inject
    lateinit var auditPublisher: AuditEventPublisher

    @Inject
    lateinit var charterRegistry: CharterRegistry

    @ConfigProperty(name = "agent.policy.enforcement", defaultValue = "advisory")
    lateinit var enforcementMode: String

    private val log = Logger.getLogger(AgentPolicyGate::class.java)

    // CodeQL java/log-injection: tool/agent/reason ultimately trace back to the MCP caller
    // (tool name) or the OPA policy response (reason text, which can echo caller input back).
    // Strip CR/LF so an attacker can't forge additional log lines (log forging, CWE-117).
    private fun String?.sanitizeForLog(): String = (this ?: "-").replace('\n', '_').replace('\r', '_')

    /**
     * Authorize a tool call. Always emits an audit event; never throws on a DENY (the caller
     * inspects [GateOutcome.proceed]). A backing-engine failure is fail-closed (DENY) inside the PDP.
     */
    fun authorize(identity: AgentIdentity?, tool: String, capability: String?, resource: String?): GateOutcome {
        val mode = parseMode()
        val decision = decide(identity, tool, capability, resource)
        audit(tool, decision, identity?.modelId ?: "unknown")

        // D9 (ADR-0031): BLOCK mode enforces the policy decision — a DENY stops the call.
        // Safety guard: if the PDP engine itself errored (pdpError=true, set by the OPA adapter on
        // connectivity failure), fall back to advisory + WARN rather than locking the assistant out
        // completely. Using the structured `pdpError` flag (not free-form reason text) prevents an
        // OPA policy rule that happens to include "unavailable" in its reason from silently bypassing
        // BLOCK mode.
        val proceed = when {
            decision.allow -> true
            mode == EnforcementMode.ADVISORY -> true
            decision.pdpError -> {
                log.warnf(
                    "agent policy BLOCK but PDP errored — falling back to ADVISORY for tool=%s; " +
                        "fix the OPA sidecar to restore enforcement. reason=%s",
                    tool.sanitizeForLog(),
                    decision.reason.sanitizeForLog(),
                )
                true
            }
            else -> false // BLOCK + PDP reachable + explicit policy DENY → block the call
        }

        if (!decision.allow) {
            // In BLOCK mode a blocked call is an operational event → log at WARN for alerting.
            if (!proceed) {
                log.warnf(
                    "agent policy BLOCK: DENIED — agent=%s tool=%s reason=%s",
                    decision.agent.sanitizeForLog(),
                    tool.sanitizeForLog(),
                    decision.reason.sanitizeForLog(),
                )
            } else {
                log.infof(
                    "agent policy %s: agent=%s tool=%s decision=DENY reason=%s proceed=%s pdpError=%s",
                    mode,
                    decision.agent.sanitizeForLog(),
                    tool.sanitizeForLog(),
                    decision.reason.sanitizeForLog(),
                    proceed,
                    decision.pdpError,
                )
            }
        }
        return GateOutcome(decision = decision, mode = mode, proceed = proceed)
    }

    private fun decide(
        identity: AgentIdentity?,
        tool: String,
        capability: String?,
        resource: String?,
    ): PolicyDecision {
        if (identity == null) {
            return PolicyDecision(
                allow = false,
                agent = "anonymous",
                tool = capability ?: tool,
                resource = resource,
                reason = "no agent identity asserted (deny-by-default)",
            )
        }
        if (capability == null) {
            return PolicyDecision(
                allow = false,
                agent = identity.agentId,
                tool = tool,
                resource = resource,
                reason = "tool '$tool' has no governance capability mapping (deny-by-default)",
            )
        }
        // ADR-0080 P0: fail-safe in-process charter allow-list. When the agent has an allow-list
        // configured (ui-assistant), a capability outside it is denied HERE — pdpError stays false,
        // so BLOCK mode blocks it regardless of OPA availability. This is what stops the pentest
        // prompt-injection (aml_list_cases is no longer in the ui-assistant charter). An empty
        // allow-list (other agents) skips this and falls through to the PDP unchanged.
        val allowed = charterRegistry.allowedCapabilities(identity.agentId)
        if (allowed.isNotEmpty() && capability !in allowed) {
            return PolicyDecision(
                allow = false,
                agent = identity.agentId,
                tool = capability,
                resource = resource,
                reason = "capability '$capability' is not in the '${identity.agentId}' charter allow-list (ADR-0080)",
            )
        }
        val attributes = buildMap<String, Any?> {
            identity.skill?.let { put("skill", it) }
        }
        return pdp.evaluate(
            PolicyQuery(
                agent = identity.agentId,
                tool = capability,
                resource = resource,
                plane = identity.plane,
                attributes = attributes,
            ),
        )
    }

    private fun audit(tool: String, decision: PolicyDecision, modelId: String) {
        val event = AuditEvent(
            actorId = decision.agent,
            actorType = "AI_AGENT",
            operation = "agent.mcp.tool_call",
            resourceType = "mcp.tool",
            resourceId = decision.resource,
            result = if (decision.allow) AuditResult.SUCCESS else AuditResult.DENIED,
            payload = mapOf(
                "tool" to tool,
                "capability" to decision.tool,
                "policy_decision" to if (decision.allow) "ALLOW" else "DENY",
                "reason" to decision.reason,
                "model_id" to modelId,
            ),
        )
        runBlocking { auditPublisher.publish(event) }
    }

    private fun parseMode(): EnforcementMode =
        runCatching { EnforcementMode.valueOf(enforcementMode.trim().uppercase()) }
            .getOrElse {
                log.warnf("unknown agent.policy.enforcement=%s, defaulting to ADVISORY", enforcementMode)
                EnforcementMode.ADVISORY
            }
}
