// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.application

import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

/**
 * Emits the AI-attributed audit event for every `tools/call` (ADR-0031 D5, ADR-0086 audit chain).
 *
 * Without this, an AI-initiated action against the bank left no record at all: the first question
 * after any incident is "what did the agent do", and `agents.rego`'s `decision` object exists
 * precisely so the MCP endpoint can answer it. The envelope is the canonical
 * [com.openbank.libs.audit.AuditEvent] — same shape agent-service publishes from its own
 * `AgentPolicyGate` (`actorType = AI_AGENT`, `policy_decision` in the payload), so both AI planes
 * land in one queryable trail rather than two dialects.
 *
 * **Every** outcome is recorded, not only the happy path — an unmapped tool and a PDP outage are
 * exactly the events an investigation looks for, and a trail that only shows successes is a trail
 * that hides the interesting half.
 *
 * PII: the envelope says **what happened**, never what came back. Tool arguments and tool results
 * are the customer's account data; only the argument *key names* are recorded, so a reviewer can
 * see the call was e.g. account-scoped without the trail becoming a second copy of the data
 * (GDPR data minimisation; `AuditEvent.payload` is documented as "NEVER raw PII").
 */
@ApplicationScoped
class McpCallAuditor(private val publisher: AuditEventPublisher) {

    /** One `tools/call` attempt and how it ended. */
    data class ToolCall(
        val agentId: String,
        val consentId: String,
        val tool: String,
        /** The ADR-0034 capability the tool maps to; `null` when the tool has no mapping at all. */
        val capability: String?,
        val decision: Decision,
        val result: AuditResult,
        val reason: String? = null,
        /** Argument KEY names only — never the values (see the class KDoc). */
        val argumentKeys: List<String> = emptyList(),
    )

    /** The PDP outcome as recorded in the payload — `UNAVAILABLE` is a deny, but a distinct one. */
    enum class Decision { ALLOW, DENY, UNAVAILABLE }

    /** One `tools/list` discovery attempt and its filter outcome (ADR-0225 D4). */
    data class ToolsList(
        val agentId: String,
        val consentId: String,
        /** Tools returned after the policy filter; 0 for anonymous discovery or a full PDP outage. */
        val toolsReturned: Int,
        val toolsTotal: Int,
        /** Capability evaluations lost to PDP transport errors (each excluded its tool, fail-closed). */
        val pdpErrors: Int,
        /** Why discovery was denied outright (e.g. "caller authentication failed"); null on success. */
        val reason: String? = null,
    )

    suspend fun toolCallCompleted(call: ToolCall) {
        publisher.publish(
            AuditEvent(
                actorId = call.agentId,
                actorType = ACTOR_TYPE,
                operation = OPERATION,
                resourceType = RESOURCE_TYPE,
                resourceId = call.tool,
                timestamp = Instant.now(),
                result = call.result,
                payload = buildMap {
                    put("tool", call.tool)
                    put("capability", call.capability)
                    put("charter", call.agentId.removePrefix(AGENT_ID_PREFIX))
                    put("consent_id", call.consentId)
                    put("policy_decision", call.decision.name)
                    put("argument_keys", call.argumentKeys)
                    call.reason?.let { put("reason", it) }
                },
            ),
        )
    }

    suspend fun toolsListCompleted(list: ToolsList) {
        publisher.publish(
            AuditEvent(
                actorId = list.agentId,
                actorType = ACTOR_TYPE,
                operation = OPERATION_TOOLS_LIST,
                resourceType = RESOURCE_TYPE,
                resourceId = TOOLS_LIST_RESOURCE_ID,
                timestamp = Instant.now(),
                result = if (list.reason != null || (list.toolsReturned == 0 && list.pdpErrors > 0)) {
                    AuditResult.DENIED
                } else {
                    AuditResult.SUCCESS
                },
                payload = buildMap {
                    put("charter", list.agentId.removePrefix(AGENT_ID_PREFIX))
                    put("consent_id", list.consentId)
                    put("tools_returned", list.toolsReturned)
                    put("tools_total", list.toolsTotal)
                    if (list.pdpErrors > 0) put("pdp_errors", list.pdpErrors)
                    list.reason?.let { put("reason", it) }
                },
            ),
        )
    }

    private companion object {
        const val ACTOR_TYPE = "AI_AGENT"

        /** `<service>.<aggregate>.<verb>` (AuditEvent KDoc); agent-service's twin is `agent.mcp.tool_call`. */
        const val OPERATION = "mcp.tool.call"

        /** Discovery (ADR-0225 D4) gets its own operation so call and reconnaissance trails filter apart. */
        const val OPERATION_TOOLS_LIST = "mcp.tools.list"
        const val RESOURCE_TYPE = "mcp.tool"
        const val TOOLS_LIST_RESOURCE_ID = "tools/list"

        /** The `agent:` prefix the shared rego strips to match a charter id in `agents.yaml`. */
        const val AGENT_ID_PREFIX = "agent:"
    }
}
