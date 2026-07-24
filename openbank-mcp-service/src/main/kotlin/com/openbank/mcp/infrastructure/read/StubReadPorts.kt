// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.read

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.mcp.application.port.out.AccountReadPort
import com.openbank.mcp.application.port.out.ConsentContext
import com.openbank.mcp.application.port.out.ProposalPort
import jakarta.enterprise.context.ApplicationScoped

/**
 * PHASE 1 deterministic stubs behind the read/proposal ports (ADR-0181). They prove the MCP
 * protocol + the OPA gate end to end without the downstream wiring. Phase 2 replaces these with
 * `@RegisterRestClient` adapters (account/balance/transaction/consent) and a real PROPOSED-row
 * maker-checker writer — the tool code and the endpoint do not change, only the bound implementation.
 */
@ApplicationScoped
class StubAccountReadPort(private val mapper: ObjectMapper) : AccountReadPort {

    override fun listAccounts(consentContext: ConsentContext): JsonNode =
        note("list_accounts", "would return the consent's grantedAccounts", consentContext)

    override fun getBalance(consentContext: ConsentContext, accountId: String): JsonNode =
        note("get_balance", "would return balance for account $accountId", consentContext)

    override fun listTransactions(consentContext: ConsentContext, accountId: String, limit: Int): JsonNode =
        note("list_transactions", "would return up to $limit transactions for account $accountId", consentContext)

    override fun listConsents(consentContext: ConsentContext): JsonNode =
        note("list_consents", "would return the agent's PSD2 consents", consentContext)

    private fun note(tool: String, what: String, ctx: ConsentContext): JsonNode = mapper.createObjectNode()
        .put("phase", "1-stub")
        .put("tool", tool)
        .put("note", "$what (real edge wiring is phase 2)")
        .put("agentId", ctx.agentId)
        .put("consentId", ctx.consentId)
}

@ApplicationScoped
class StubProposalPort(private val mapper: ObjectMapper) : ProposalPort {
    override fun proposePayment(consentContext: ConsentContext, request: JsonNode): JsonNode = mapper.createObjectNode()
        .put("phase", "1-stub")
        .put("status", "PROPOSED")
        .put(
            "note",
            "a reviewable proposal; no money moves — HITL + SCA disposes (phase 2 writes the maker-checker row)",
        )
        .put("agentId", consentContext.agentId)
        .set("request", request)
}
