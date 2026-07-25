// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.application

import com.openbank.copilot.application.port.out.ToolPolicyPort
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Deny-by-default authorization for every tool call (ADR-0089 D3; ADR-0034 D1).
 *
 * Two-layer gate (D4 router + narrator enforcement):
 *   1. **Application whitelist** — capability-based allow/deny. Runs first, always.
 *   2. **OPA sidecar** — per-tool, per-amount, per-customer policy evaluation
 *      (`data.openbank.copilot.tool.allow`). Runs when [opaEnforce] is `true`; advisory-only
 *      (logs but allows through) when `false` (default — ADR-0067 staged rollout, issue #998).
 *
 * The whitelist enforces that ONLY the router + narrator tools reachable by the model are
 * the closed set declared here (D3 closed whitelist). OPA enforcement wires the per-session
 * amount ceiling and future per-customer policy (D5 least-privilege). Both layers must allow
 * for the tool to proceed — a whitelist deny short-circuits before OPA.
 *
 * Every decision is audited (ADR-0031 D5): capability, OPA verdict, reason.
 */
@ApplicationScoped
class CopilotPolicyGate(
    private val auditPublisher: AuditEventPublisher,
    private val opaGate: ToolPolicyPort,
    @ConfigProperty(name = "copilot.opa.enforce", defaultValue = "false")
    private val opaEnforce: Boolean,
) {
    private val log = Logger.getLogger(CopilotPolicyGate::class.java)

    data class Decision(val allow: Boolean, val reason: String)

    suspend fun authorize(customerId: String, tool: String, capability: String?): Decision {
        // Layer 1: application-level whitelist (deny-by-default).
        val whitelisted = capability != null && (capability in READ_WHITELIST || capability in ACTION_WHITELIST)
        val whitelistReason = when {
            capability == null -> "not-permitted (deny-by-default)"
            capability in READ_WHITELIST -> "read-whitelist"
            capability in ACTION_WHITELIST -> "action-whitelist (propose-only; HITL + SCA downstream)"
            else -> "not-permitted (deny-by-default)"
        }
        if (!whitelisted) {
            log.warnf("policy denied tool=%s capability=%s reason=%s", tool, capability, whitelistReason)
            audit(customerId, tool, capability, AuditResult.DENIED, whitelistReason, opaVerdict = null)
            return Decision(allow = false, reason = whitelistReason)
        }

        // Layer 2: OPA sidecar — per-tool, per-amount policy (advisory or enforce mode). The port
        // is fail-closed: unreachable, non-2xx and unparseable all arrive here as a plain deny with
        // a distinguishing reason, so this branch never has to tell a policy deny from a transport
        // bug by inspecting an exception type.
        val opa = opaGate.authorize(tool, customerId)
        val opaVerdict = if (opa.allow) "allow" else "deny"
        if (!opa.allow) {
            if (opaEnforce) {
                log.warnf(
                    "OPA gate denied tool=%s customer=%s reason=%s (enforce mode)",
                    tool,
                    customerId,
                    opa.reason,
                )
                audit(customerId, tool, capability, AuditResult.DENIED, opa.reason, opaVerdict = "deny")
                return Decision(allow = false, reason = opa.reason)
            } else {
                // Advisory mode: log and pass through.
                log.warnf(
                    "OPA advisory-denied (allowing) tool=%s cust=%s reason=%s — " +
                        "flip copilot.opa.enforce=true to block",
                    tool,
                    customerId,
                    opa.reason,
                )
            }
        }

        val finalReason = "$whitelistReason; opa=$opaVerdict"
        audit(customerId, tool, capability, AuditResult.SUCCESS, finalReason, opaVerdict = opaVerdict)
        return Decision(allow = true, reason = finalReason)
    }

    @Suppress("LongParameterList")
    private suspend fun audit(
        customerId: String,
        tool: String,
        capability: String?,
        result: AuditResult,
        reason: String,
        opaVerdict: String?,
    ) {
        auditPublisher.publish(
            AuditEvent(
                actorId = customerId,
                actorType = "AI_AGENT",
                operation = "copilot.tool.authorize",
                resourceType = "copilot.tool",
                resourceId = tool,
                result = result,
                payload = buildMap {
                    put("capability", capability)
                    put("reason", reason)
                    if (opaVerdict != null) put("opa_verdict", opaVerdict)
                    put("opa_enforce", opaEnforce)
                },
            ),
        )
    }

    private companion object {
        // READ-only capabilities — tool narrates figures from results, never invents them (ADR-0089 D4).
        val READ_WHITELIST = setOf(
            "account.read",
            "account.balance.read",
            "account.transactions.read",
            "account.statements.read",
            "account.scheduled-payments.read",
            "fx.rates.read",
            "card.status.read",
            "help.search.read",
        )

        // Money-path ACTION capabilities (ADR-0089 D2, Phase 2). These authorise a *proposal* only —
        // the action is never executed by the assistant; HITL + SCA (dynamic linking) enforce
        // execution downstream in the existing edge flow.
        val ACTION_WHITELIST = setOf(
            "payment.propose",
            "card.freeze.propose",
            "dispute.open.propose",
            "fx.conversion.propose",
        )
    }
}
