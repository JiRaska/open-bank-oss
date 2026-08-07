// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-edge nearby-payment session store (ADR-0087). The receiver creates a short-lived session
 * bound to ONE of their own accounts and broadcasts the returned opaque token over BLE; the payer
 * resolves the token to a display name + requested amount + a MASKED account before signing. Only
 * the edge can map the token back to the real account, so nothing the payer holds BEFORE paying
 * discloses it.
 *
 * That is the whole of the guarantee, and it is a request-side one. It is not "the real creditor
 * account never leaves the edge": the edge sends it upstream to address the instruction, and the
 * payment confirmation returns it to the payer verbatim (issue #3890, pinned by
 * NearbyPayCreditorDisclosureTest). The mask defeats free harvesting through the directory, not
 * disclosure to someone who has actually paid.
 *
 * Why a store inside the edge rather than a microservice: the session is an ephemeral, ~minutes-TTL
 * token→creditor mapping with no reporting/ledger value once it expires. A dedicated service +
 * Postgres would add a deploy/gitops surface for data that is gone in five minutes. Keeping the
 * token opaque (a random id, never an encoded payload) is also what lets the resolution rule evolve
 * later — e.g. a standardised cross-app "pay via your bank app" payload — without changing the
 * token the receiver broadcasts.
 *
 * In-memory is acceptable because the loss mode is benign: an edge restart drops live sessions, the
 * payer's resolve returns 404, and the receiver simply re-shares. For multi-replica edge the token
 * would need a shared TTL cache (Redis) — tracked as a follow-up; single-replica sandbox is fine.
 */
@ApplicationScoped
class PaymentSessionStore {

    data class Session(
        val creditorAccountId: String,
        val creditorPartyId: String,
        val displayName: String,
        val requestedAmount: String?,
        val creditorMasked: String,
        val expiresAt: Long,
        val paid: Boolean = false,
        /**
         * The payer's domestic-payment id, attached once they initiate the payment for this session.
         * Lets the receiver-side status poll reconcile true settlement (ADR-0108) from
         * domestic-payment rather than trusting mere instruction acceptance: [paid] is only set once
         * that payment reaches SETTLED, not when the create call returns 2xx. Null until a payer pays.
         */
        val paymentId: String? = null,
    )

    private val sessions = ConcurrentHashMap<String, Session>()

    /**
     * Bind a new session to the receiver's [creditorAccountId] (already ownership-checked by the
     * caller) and return the opaque token to broadcast. [creditorMasked] is the only account form a
     * payer ever sees; the real id stays here. The token is a random hex id with no embedded data.
     */
    fun create(
        creditorAccountId: String,
        creditorPartyId: String,
        displayName: String,
        requestedAmount: String?,
        creditorMasked: String,
    ): String {
        purgeExpired()
        val token = UUID.randomUUID().toString().replace("-", "")
        sessions[token] = Session(
            creditorAccountId = creditorAccountId,
            creditorPartyId = creditorPartyId,
            displayName = displayName,
            requestedAmount = requestedAmount,
            creditorMasked = creditorMasked,
            expiresAt = System.currentTimeMillis() + TTL_MS,
        )
        return token
    }

    /** Resolve a discovered token, or null if unknown/expired. Expired entries are evicted on read. */
    fun resolve(token: String): Session? {
        val session = sessions[token] ?: return null
        if (System.currentTimeMillis() >= session.expiresAt) {
            sessions.remove(token)
            return null
        }
        return session
    }

    /** Mark a session as paid once the payer's domestic payment has actually SETTLED (ADR-0108). */
    fun markPaid(token: String) {
        sessions.computeIfPresent(token) { _, s -> s.copy(paid = true) }
    }

    /**
     * Bind the payer's domestic-payment id to the session at instruction time. Idempotent and
     * first-write-wins: a session is paid by exactly one payer, so a later token reuse must not
     * overwrite the original payment id. Lets [paymentSessionStatus] later reconcile real settlement.
     */
    fun attachPayment(token: String, paymentId: String) {
        sessions.computeIfPresent(token) { _, s ->
            if (s.paymentId.isNullOrBlank()) s.copy(paymentId = paymentId) else s
        }
    }

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { now >= it.value.expiresAt }
    }

    companion object {
        // BLE round-trips and the payer's "confirm before sign" step are seconds, not minutes;
        // 5 minutes is generous headroom while keeping a lost/leaked token short-lived.
        const val TTL_MS = 5 * 60 * 1000L

        // IBAN masking: show the 2-char country code + last 4, hide the middle.
        private const val MIN_MASKABLE_LEN = 6
        private const val IBAN_PREFIX_LEN = 2
        private const val IBAN_TAIL_LEN = 4

        /**
         * Mask a Czech IBAN to country + last 4 ("CZ…6789"), the only account form a payer is shown
         * (ADR-0087). Falls back to a generic mask for anything not IBAN-shaped. Package-visible for
         * unit tests.
         */
        fun maskIban(iban: String?): String {
            val clean = iban?.replace(" ", "").orEmpty()
            if (clean.length < MIN_MASKABLE_LEN) return "CZ…0000"
            return clean.take(IBAN_PREFIX_LEN) + "…" + clean.takeLast(IBAN_TAIL_LEN)
        }
    }
}
