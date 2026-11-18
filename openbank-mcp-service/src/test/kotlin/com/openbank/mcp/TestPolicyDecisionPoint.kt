// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.openbank.libs.authz.AuthzDecision
import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.PolicyDecisionPoint
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative

/**
 * Stands in for the OPA sidecar, which does not exist in a test JVM (`AuthzProducer` would produce
 * a client against `localhost:8181` and every call would fail closed — a real posture, but it makes
 * the ALLOW branch unreachable).
 *
 * Deliberately deterministic rather than a mock: `list_accounts` is allowed, everything else is
 * denied, so one running container serves both the allow and the deny assertion without
 * per-test stubbing.
 */
@ApplicationScoped
@Alternative
@Priority(1)
class TestPolicyDecisionPoint : PolicyDecisionPoint {
    override suspend fun allow(query: AuthzQuery): AuthzDecision {
        if (query.attributes["channel"] == CUSTOMER_CHANNEL) return customer(query)
        return staff(query)
    }

    /**
     * The customer channel (ADR-0383 D4.1): mirrors `mcp_rest_ext.rego` for the IT — the read tool
     * under its own action, only with the matching granted capability; [customerOutage] / [customerDeny]
     * let McpCustomerDelegationIT drive the PDP's failure and deny branches. Every query is recorded.
     */
    private fun customer(query: AuthzQuery): AuthzDecision {
        customerQueries += query
        if (customerOutage) error("opa unavailable")
        val need = CUSTOMER_TOOLS[query.attributes["tool"]]
        val granted = query.attributes["grantedCapabilities"] as? List<*> ?: emptyList<Any>()
        val ok = !customerDeny && need != null && query.action == need.first && need.second in granted &&
            !query.principal.serviceAccount && query.principal.roles.isEmpty()
        return if (ok) AuthzDecision(allow = true) else AuthzDecision(allow = false, reason = "customer channel denied")
    }

    private fun staff(query: AuthzQuery): AuthzDecision = if (allowEverything ||
        query.attributes["tool"] == ALLOWED_TOOL ||
        query.action.startsWith(SESSION_ACTION_PREFIX)
    ) {
        AuthzDecision(allow = true)
    } else {
        AuthzDecision(allow = false, reason = "no matching allow rule")
    }

    companion object {
        const val CUSTOMER_CHANNEL = "mcp-customer"
        val CUSTOMER_TOOLS = mapOf(
            "list_accounts" to ("query.account.readonly" to "ACCOUNT_VIEW_DETAILS"),
            "get_balance" to ("query.balance.readonly" to "ACCOUNT_READ_BALANCES"),
            "list_transactions" to ("query.transaction.readonly" to "ACCOUNT_READ_TRANSACTIONS"),
        )
        val customerQueries: MutableList<AuthzQuery> = java.util.concurrent.CopyOnWriteArrayList()

        @Volatile
        var customerDeny: Boolean = false

        @Volatile
        var customerOutage: Boolean = false

        const val ALLOWED_TOOL = "list_accounts"
        const val DENIED_TOOL = "list_consents"
        const val SESSION_ACTION_PREFIX = "mcp.session."

        /**
         * Opened ONLY by the wire-level fuzz test (McpToolCallWireFuzzIT), which sets it in
         * `@BeforeEach` and resets it in `@AfterEach` (run even if setup failed), so that every
         * tool's argument handling is reachable over real HTTP. Off by default: every
         * other test keeps the deterministic allow-one/deny-rest behaviour described above.
         */
        @Volatile
        var allowEverything: Boolean = false
    }
}
