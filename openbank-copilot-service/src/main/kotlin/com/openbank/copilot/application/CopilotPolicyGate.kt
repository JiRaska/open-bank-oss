// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
package com.openbank.copilot.application

import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * Deny-by-default authorization for every tool call (ADR-0089 D3; ADR-0034). Phase 1 enforces a
 * closed whitelist of READ-only capabilities; an OPA-backed decision point
 * (`data.openbank.copilot.allow`) is the enforce-phase follow-up (#998). Customer-scoping is
 * additionally enforced downstream (the call runs as the customer), so this gate bounds WHICH
 * capabilities the assistant may use at all — action capabilities are deliberately absent until the
 * HITL + SCA path lands (Phase 2, ADR-0089 D2). Every decision is audited (ADR-0031 D5).
 */
@ApplicationScoped
class CopilotPolicyGate(private val auditPublisher: AuditEventPublisher) {
    private val log = Logger.getLogger(CopilotPolicyGate::class.java)

    data class Decision(val allow: Boolean, val reason: String)

    suspend fun authorize(customerId: String, tool: String, capability: String?): Decision {
        val allow = capability != null && (capability in READ_WHITELIST || capability in ACTION_WHITELIST)
        val reason = when {
            capability == null -> "not-permitted (deny-by-default)"
            capability in READ_WHITELIST -> "read-whitelist"
            capability in ACTION_WHITELIST -> "action-whitelist (propose-only; HITL + SCA downstream)"
            else -> "not-permitted (deny-by-default)"
        }
        val decision = Decision(allow, reason)
        if (!allow) log.warnf("policy denied tool=%s capability=%s", tool, capability)
        auditPublisher.publish(
            AuditEvent(
                actorId = customerId,
                actorType = "AI_AGENT",
                operation = "copilot.tool.authorize",
                resourceType = "copilot.tool",
                resourceId = tool,
                result = if (allow) AuditResult.SUCCESS else AuditResult.DENIED,
                payload = mapOf("capability" to capability, "reason" to decision.reason),
            ),
        )
        return decision
    }

    private companion object {
        // READ-only capabilities (own data only).
        val READ_WHITELIST = setOf(
            "account.read",
            "account.balance.read",
            "account.transactions.read",
            "account.statements.read",
            "fx.rates.read",
            "card.status.read",
            "help.search.read",
        )

        // Money-path ACTION capabilities (Phase 2). These authorise a *proposal* only — the action is
        // never executed by the assistant; HITL + SCA (dynamic linking) enforce execution downstream
        // in the existing edge flow (ADR-0089 D2). A capability absent here is denied by default.
        val ACTION_WHITELIST = setOf(
            "payment.propose",
            "card.freeze.propose",
            "dispute.open.propose",
            "fx.conversion.propose",
        )
    }
}
