// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.copilot.application

import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import com.openbank.libs.llm.ContentSafetyMetricsPort
import com.openbank.libs.llm.ContentSafetyPort
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * The copilot's policy over the [ContentSafetyPort] verdicts (ADR-0031 guardrails, ADR-0089 D3) —
 * the model-based companion to the deterministic [PromptInjectionGuard].
 *
 * Two checks, deliberately asymmetric:
 *  - **input** ([checkUserInput]) — the customer message, before the model sees it;
 *  - **output** ([checkAssistantOutput]) — what the copilot is about to say back. Cheap and worth
 *    it: an unsafe completion is not predictable from a safe-looking input, and this is the only
 *    check positioned to catch one.
 *
 * ## What an unavailable classifier means here
 *
 * `copilot.content-safety.fail-closed` decides, and it is `false` by default for the CUSTOMER HELP
 * surface: refusing to answer "what is my balance" because a classifier is unreachable is a worse
 * outcome than answering it, and the deterministic injection guard plus the OPA tool gate plus SCA
 * still stand between the customer and any action. It must be flipped to `true` wherever the same
 * port fronts a state-changing surface. The choice is explicit at every call site
 * ([ContentSafetyPort.SafetyVerdict.isBlocking] takes it as an argument and has no default) because
 * the wrong default here is the whole defect class: a guardrail that silently reports success when
 * it never ran.
 *
 * Every non-safe verdict is audited (AI-attributed, ADR-0031 D5) and counted with the `blocked`
 * label reflecting what THIS policy did — so `decision="unavailable", blocked="false"` is legible
 * as "degraded and allowed" rather than hiding inside an aggregate.
 */
@ApplicationScoped
class ContentSafetyGuard(
    private val safety: ContentSafetyPort,
    private val auditPublisher: AuditEventPublisher,
    private val metrics: ContentSafetyMetricsPort,
    // Constructor injection with NO Kotlin default. A `= false` here would generate a synthetic
    // constructor, Arc would build the bean through it, and the @ConfigProperty would never be
    // consulted — the flag would read `false` whatever the environment said, which for a
    // fail-closed switch is the worst possible direction to be stuck in. Enforced by the
    // `configproperty-kotlin-defaults` gate; the annotation's own defaultValue is the fallback.
    @ConfigProperty(name = "copilot.content-safety.fail-closed", defaultValue = "false")
    private val failClosed: Boolean,
) {

    private val log = Logger.getLogger(ContentSafetyGuard::class.java)

    /** True when the customer message must not reach the model. */
    suspend fun checkUserInput(customerId: String, text: String): Boolean =
        check(customerId, ContentSafetyPort.SafetyRole.USER, text)

    /** True when the drafted reply must not reach the customer. */
    suspend fun checkAssistantOutput(customerId: String, text: String): Boolean =
        check(customerId, ContentSafetyPort.SafetyRole.ASSISTANT, text)

    private suspend fun check(customerId: String, role: ContentSafetyPort.SafetyRole, text: String): Boolean {
        if (text.isBlank()) return false
        val verdict = safety.classify(role, text)
        val blocked = verdict.isBlocking(failClosed)
        if (verdict.decision == ContentSafetyPort.Decision.SAFE) return false

        log.warnf(
            "content-safety: decision=%s role=%s categories=%s reason=%s action=%s",
            verdict.decision,
            role,
            verdict.categories.joinToString(","),
            verdict.reason,
            if (blocked) "blocked" else "allowed",
        )
        // Re-reported with the policy outcome the adapter could not know. Both rows exist on
        // purpose: the adapter's says what the model decided, this one says what the bank did.
        metrics.recordClassification(verdict.model, role.name.lowercase(), verdict.decision.name.lowercase(), blocked)
        auditPublisher.publish(
            AuditEvent(
                actorId = customerId,
                actorType = "AI_AGENT",
                operation = "copilot.guardrail.content_safety",
                resourceType = "copilot",
                resourceId = customerId,
                result = if (blocked) AuditResult.DENIED else AuditResult.SUCCESS,
                payload = mapOf(
                    // ADR-0031 D5 names this key `model_id` fleet-wide, and agent-service has a
                    // test that enforces it on every AI_AGENT event. Same rule, same key here —
                    // and it is the CLASSIFIER's model, not the chat model.
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
