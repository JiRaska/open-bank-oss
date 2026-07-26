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
 * Campaign-planning reach, for the ADR-0209 D5 campaign-copilot. AGGREGATE ONLY.
 *
 * Two things about this port are deliberate and easy to undo by accident.
 *
 * **It returns counts, never people.** A copilot planning a campaign needs the reach of a scope, not
 * the identities inside it. Who actually receives anything is campaign-service's concern under
 * ADR-0200's consent-gated delivery, which ADR-0209 D5 explicitly does not authorise here. Returning
 * an aggregate means no personal data leaves the bank on this path at all — a stronger property than
 * masking it on the way out, because there is nothing to mask.
 *
 * **It takes no [ConsentContext], and that absence is the documentation.** Every other read on this
 * surface is gated TWICE: the OPA charter capability, and the intersection with the PSD2 consent the
 * caller presented ([AccountReadPort]). This query has no such consent to intersect with — it is an
 * operator-plane question about the bank's own marketing reach, not about one customer's data — so it
 * is gated by the charter capability ALONE. Accepting a ConsentContext it did not use would imply a
 * scoping that does not happen, and a reviewer comparing this port to AccountReadPort would read two
 * gates where there is one. Never add the parameter to make the signatures look alike.
 *
 * Consequence to keep in view: the capability that grants this is therefore the whole control. It
 * must never be added to a charter that does not need it, and the charter must stay read-only —
 * ADR-0209 D5 bars this tool from ever acquiring a write.
 */
interface MarketingReachPort {
    /**
     * Reach per marketing scope, from `consent-service`'s authoritative view of the ADR-0205 D3
     * internal grantee `party-service:marketing-comms`. Counts of ACTIVE consents by scope, plus
     * `asOf`, so a caller can see the answer's age instead of assuming it is live (ADR-0210 D3).
     */
    fun countMarketingConsents(): JsonNode
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
