// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application

import com.openbank.agent.domain.policy.AgentIdentity
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import com.openbank.libs.llm.ContentSafetyMetricsPort
import com.openbank.libs.llm.ContentSafetyPort
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Model-based content safety for the CONTROL-PLANE reasoning loop (ADR-0031 guardrails,
 * ADR-0265 slice 2) — the same Llama Guard classifier the customer copilot uses, on the operator
 * side, beside the deterministic [PromptInjectionGuard].
 *
 * ## Why this exists at all when the operator is a trusted human
 *
 * The trust boundary is not the operator, it is the DATA. Everything a tool returns — a transaction
 * memo, a merchant name, a proposal title someone typed weeks ago — reaches the model as text, and
 * the deterministic filter matches phrasings we already know. The classifier is what covers the
 * ones we do not, and the output side is where an operator-facing surface is most exposed: an
 * assistant that renders unsafe content into an admin screen has laundered it through a trusted UI.
 *
 * ## Posture
 *
 * `agent.content-safety.fail-closed` decides what an unreachable classifier means, and defaults to
 * FALSE here for the same reason as on the help surface: refusing an operator's read-only question
 * because a classifier is down is a worse outcome than answering it, and the charter tool allow-list,
 * the OPA gate and the HITL proposal queue all still bound what any answer can cause. Every
 * unavailable verdict is audited and counted regardless, so the degraded state is legible rather
 * than assumed — that visibility is the whole reason the verdict is three-valued.
 */
@ApplicationScoped
class AgentContentSafetyGuard(
    private val safety: ContentSafetyPort,
    private val auditPublisher: AuditEventPublisher,
    private val metrics: ContentSafetyMetricsPort,
    // No Kotlin default on a @ConfigProperty parameter: a defaulted one makes Arc build the bean
    // through a synthetic constructor and skip config entirely, which for a fail-closed switch
    // means it could never be turned on.
    @ConfigProperty(name = "agent.content-safety.fail-closed", defaultValue = "false")
    private val failClosed: Boolean,
) {

    private val log = Logger.getLogger(AgentContentSafetyGuard::class.java)

    /** True when the operator's message must not reach the model. */
    suspend fun checkUserInput(identity: AgentIdentity, text: String): Boolean =
        check(identity, ContentSafetyPort.SafetyRole.USER, text)

    /** True when the drafted answer must not reach the operator. */
    suspend fun checkAssistantOutput(identity: AgentIdentity, text: String): Boolean =
        check(identity, ContentSafetyPort.SafetyRole.ASSISTANT, text)

    private suspend fun check(identity: AgentIdentity, role: ContentSafetyPort.SafetyRole, text: String): Boolean {
        if (text.isBlank()) return false
        val verdict = safety.classify(role, text)
        if (verdict.decision == ContentSafetyPort.Decision.SAFE) return false
        val blocked = verdict.isBlocking(failClosed)

        log.warnf(
            "guardrail: content-safety decision=%s agent=%s role=%s categories=%s reason=%s action=%s",
            verdict.decision,
            identity.agentId,
            role,
            verdict.categories.joinToString(","),
            verdict.reason,
            if (blocked) "blocked" else "allowed",
        )
        metrics.recordClassification(verdict.model, role.name.lowercase(), verdict.decision.name.lowercase(), blocked)
        auditPublisher.publish(
            AuditEvent(
                actorId = identity.agentId,
                actorType = "AI_AGENT",
                operation = "agent.guardrail.content_safety",
                resourceType = "agent",
                resourceId = identity.agentId,
                result = if (blocked) AuditResult.DENIED else AuditResult.SUCCESS,
                payload = mapOf(
                    // ADR-0031 D5: every AI-attributed event names the model that acted. Here that
                    // is the CLASSIFIER, not the chat model — the two are different models and the
                    // one that produced this verdict is the one an auditor needs.
                    "model_id" to verdict.model,
                    "decision" to verdict.decision.name.lowercase(),
                    "categories" to verdict.categories.joinToString(","),
                    "reason" to verdict.reason,
                    "role" to role.name.lowercase(),
                    "fail_closed" to failClosed.toString(),
                    "action" to if (blocked) "blocked" else "allowed",
                ),
            ),
        )
        return blocked
    }
}
