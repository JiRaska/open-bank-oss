// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import jakarta.ws.rs.core.Response

/**
 * ADR-0383 D6: what a proposal approval adds to the ordinary payment settlement gate.
 *
 *  - [linking]: extra dynamic-linking fields the challenge must ALSO match (approvalRequestId +
 *    payloadSha256). Additive only: the gate never lets them replace amount/currency/payee.
 *  - [onConsumed]: runs inside the gate, AFTER the customer's challenge was spent and BEFORE the
 *    payment is created — the store's approve CAS. A non-null answer aborts the payment: the gate
 *    returns it and no payment request is ever sent.
 *
 * One instance per request, never shared, so the flags answer for exactly one approval.
 */
class ProposalScaBinding(val linking: Map<String, String>, private val onConsumed: () -> Response? = { null }) {
    @Volatile
    var consumed: Boolean = false
        private set

    @Volatile
    var storeApproved: Boolean = false
        private set

    /** Called by the settlement gate once the challenge is spent; null lets the payment proceed. */
    internal fun challengeSpent(): Response? {
        consumed = true
        val refusal = onConsumed()
        storeApproved = refusal == null
        return refusal
    }
}
