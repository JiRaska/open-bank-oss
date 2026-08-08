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
 * The read surface behind `query.statement.readonly` (issue #4109, ADR-0248). Sourced from
 * statement-service's existing period-close record + the new JSON summary projection this PR adds
 * there — never from a rendered camt.053/MT940/PDF, which a calling model cannot reason over.
 *
 * [accountId] is the caller-presented **IBAN**, matching every other tool on this surface
 * ([AccountReadPort] KDoc) — the adapter resolves it to the account-service UUID statement-service
 * is keyed by. When [legalSequence] is omitted the adapter reads the most recent CLOSED period for
 * [currency] (or, if that is also omitted, the account's most recently closed pocket) from
 * statement-service's own period list — a caller asking "summarize my March statement" does not
 * necessarily know its legal sequence number.
 */
interface StatementReadPort {
    fun getStatementSummary(
        consentContext: ConsentContext,
        accountId: String,
        currency: String?,
        legalSequence: Long?,
    ): JsonNode
}

/**
 * The read surface behind `query.payment_confirmation.readonly` (issue #4109, ADR-0248). [paymentId]
 * names either a SEPA or a domestic payment — the two rails are separate services with no shared
 * lookup (customer-edge itself routes to one or the other by caller-selected route, never by
 * probing both — `CustomerEdgeResource`'s `/domestic-payments/{paymentId}` and
 * `/sepa-payments/{paymentId}` are distinct endpoints). An MCP caller supplies one opaque payment
 * reference with no rail hint, so the adapter tries SEPA first, then domestic, and reports "not
 * found" only once both have said so.
 */
interface PaymentConfirmationReadPort {
    fun getPaymentConfirmation(consentContext: ConsentContext, paymentId: String): JsonNode
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
 *
 * [actChain] and [sessionId] are the ADR-0226 audit-correlation dimensions, parsed from the
 * token's RFC 8693 `act` nesting and `sid` claim (ADR-0224): empty/null for a direct caller,
 * populated once OBO sessions exist — the audit trail carries them from day one so a mediated
 * action is attributable to the identity that opened the session.
 */
data class ConsentContext(
    val agentId: String,
    val consentId: String,
    val grantedAccounts: List<String>,
    val actChain: List<String> = emptyList(),
    val sessionId: String? = null,
    /**
     * OPA principal classification — `AI_AGENT` for `agent:` tokens (consent-bound, ADR-0195),
     * `HUMAN` for OBO-exchanged staff tokens (ADR-0224). Drives which identity shape the PDP and
     * the audit trail see; a HUMAN context carries no consent and its [roles] come from the
     * exchanged token's realm_access, already bounded at issuance.
     */
    val principalType: String = "AI_AGENT",
    /** Realm roles of the caller (empty for agent tokens — agents are charter-gated, not role-gated). */
    val roles: List<String> = emptyList(),
)
