// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.mcp.application.port.out.AccountReadPort
import com.openbank.mcp.application.port.out.ConsentContext
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative

/**
 * Stands in for [com.openbank.mcp.infrastructure.read.RealAccountReadPort], which calls the real
 * account/balance/transaction/consent-service REST clients — none of which exist in a test JVM
 * (same reason [TestPolicyDecisionPoint] stands in for the OPA sidecar). Deterministic rather than
 * a mock, mirroring that class's own convention.
 */
@ApplicationScoped
@Alternative
@Priority(1)
class TestAccountReadPort(private val mapper: ObjectMapper) : AccountReadPort {
    override fun listAccounts(consentContext: ConsentContext): JsonNode = note("list_accounts", consentContext)
    override fun getBalance(consentContext: ConsentContext, accountId: String): JsonNode =
        note("get_balance", consentContext)
    override fun listTransactions(consentContext: ConsentContext, accountId: String, limit: Int): JsonNode =
        note("list_transactions", consentContext)
    override fun listConsents(consentContext: ConsentContext): JsonNode = note("list_consents", consentContext)

    private fun note(tool: String, ctx: ConsentContext): JsonNode = mapper.createObjectNode()
        .put("test", true)
        .put("tool", tool)
        .put("agentId", ctx.agentId)
        .put("consentId", ctx.consentId)
}
