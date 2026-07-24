// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.application.port.out

import com.fasterxml.jackson.databind.JsonNode

/**
 * The read surface the MCP tools delegate to (ADR-0181). Each method maps to an existing
 * consent-scoped read on another service (account/balance/transaction/consent) — the MCP tools are
 * thin adapters, they never reimplement a domain read. Phase 1 binds a deterministic stub; phase 2
 * binds the real `@RegisterRestClient` clients behind the same port (agent-service's ServiceClients
 * pattern), so the tool code does not change when the wiring lands.
 *
 * `consentContext` carries the PSD2 consent the caller presented — the scope an MCP agent may reach
 * is the intersection of its charter and this consent's `grantedAccounts` (ADR-0126). The port
 * implementation is responsible for enforcing that intersection; a tool never sees an account the
 * consent did not grant.
 */
interface AccountReadPort {
    fun listAccounts(consentContext: ConsentContext): JsonNode
    fun getBalance(consentContext: ConsentContext, accountId: String): JsonNode
    fun listTransactions(consentContext: ConsentContext, accountId: String, limit: Int): JsonNode
    fun listConsents(consentContext: ConsentContext): JsonNode
}

/**
 * Produces a reviewable payment PROPOSAL (never a debit). Mirrors agent-service's HITL draft_ticket:
 * the model proposes, a human disposes — money never moves on the model's word (ADR-0031, ADR-0181).
 * Phase 1 returns a proposal id from an in-memory stub; phase 2 writes a PROPOSED row into the
 * maker-checker queue a human operator already uses, and the app flow routes it through SCA
 * dynamic-linking before any state change.
 */
interface ProposalPort {
    fun proposePayment(consentContext: ConsentContext, request: JsonNode): JsonNode
}

/**
 * The presented PSD2 consent + the acting agent identity, resolved from the caller's OAuth token
 * (phase 2). `agentId` is the OPA principal id; `grantedAccounts` bounds the reachable scope.
 */
data class ConsentContext(val agentId: String, val consentId: String, val grantedAccounts: List<String>)
