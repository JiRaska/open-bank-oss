// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.agent.application

import com.openbank.agent.domain.model.ChatMessage
import com.openbank.agent.domain.policy.AgentIdentity
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.security.MessageDigest

/**
 * Emits the run-level AI-attribution audit event (ADR-0031 D5). Per-step events already exist —
 * [AgentPolicyGate] audits every ALLOW/DENY decision, [McpToolRegistry] every execution outcome,
 * [ModelGateway] every completion. What was missing is the single event that ties a whole agent
 * run together under the agent's identity: which model, which prompt (hashed — never the raw,
 * possibly-PII content), which tools ran with what policy outcome, how many tokens, and whether
 * the run produced a proposal. This is the record a DORA Art. 17 reconstruction starts from; the
 * per-step events hang off it via the shared traceId.
 */
@ApplicationScoped
class AgentRunAuditor {

    @Inject
    lateinit var auditPublisher: AuditEventPublisher

    /** Everything the D5 run-level event records about one agent run. */
    data class AgentRun(
        val identity: AgentIdentity,
        val trigger: String,
        val modelId: String,
        val promptHash: String,
        val toolCalls: List<AgentChatService.ToolCallRecord>,
        val totalTokens: Long,
        val isProposal: Boolean,
        val result: AuditResult,
        val detail: String? = null,
    )

    suspend fun runCompleted(run: AgentRun) {
        auditPublisher.publish(
            AuditEvent(
                actorId = run.identity.agentId,
                actorType = "AI_AGENT",
                operation = "agent.run",
                resourceType = "agent",
                resourceId = run.identity.agentId,
                result = run.result,
                payload = buildMap {
                    put("plane", run.identity.plane)
                    put("trigger", run.trigger)
                    put("model_id", run.modelId)
                    put("prompt_hash", run.promptHash)
                    put("tool_calls", run.toolCalls.map { mapOf("tool" to it.tool, "allowed" to it.allowed) })
                    put("total_tokens", run.totalTokens)
                    put("is_proposal", run.isProposal)
                    run.detail?.let { put("detail", it) }
                },
            ),
        )
    }

    companion object {
        /**
         * SHA-256 over the prompt material — same `role:content` convention as the per-completion
         * hash in [ModelGateway], so a run-level hash of an unchanged single-turn prompt matches
         * the completion-level one.
         */
        fun promptHash(messages: List<ChatMessage>): String {
            val material = messages.joinToString("\n") { "${it.role}:${it.content}" }
            return MessageDigest.getInstance("SHA-256")
                .digest(material.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}
