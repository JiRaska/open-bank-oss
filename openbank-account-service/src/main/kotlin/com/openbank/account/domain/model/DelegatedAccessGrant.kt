// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.domain.model

import java.time.OffsetDateTime
import java.util.UUID

/**
 * account-service's local projection row of a delegation-service grant (ADR-0232 D3).
 *
 * Capabilities stay STRINGS, not an enum: the delegation vocabulary is owned by
 * delegation-service and will grow (savings, objects, SME). An unknown value must
 * never poison the consumer — it is stored and simply never matches a guard
 * question this service knows how to ask. Only the ACCOUNT-scoped capabilities
 * below participate in enforcement.
 */
data class DelegatedAccessGrant(
    val id: UUID,
    val accountId: UUID,
    /**
     * The party that issued the grant. Carried so the guard can ask the question this projection
     * could not: does the grantor actually OWN the account?
     *
     * Without it a grant row is authority in itself — the guard matched on
     * (accountId, granteePartyId) alone, so a grant naming a stranger's account produced real,
     * enforced access to that account. delegation-service now verifies ownership at offer time
     * too; this is the enforcing side, and it is the one that re-reads the truth on every call
     * rather than trusting a check that passed once.
     */
    val grantorPartyId: UUID,
    val granteePartyId: UUID,
    val capabilities: Set<String>,
    val perTransactionLimitAmount: java.math.BigDecimal? = null,
    val perTransactionLimitCurrency: String? = null,
    val validFrom: OffsetDateTime,
    val validTo: OffsetDateTime? = null,
    val active: Boolean = true,
) {
    /** The grant only speaks for the account if the party who issued it owns the account. */
    fun issuedBy(ownerPartyId: UUID): Boolean = grantorPartyId == ownerPartyId

    fun isActiveOn(now: OffsetDateTime): Boolean = active &&
        !now.isBefore(validFrom) &&
        (validTo == null || now.isBefore(validTo))

    fun satisfies(role: AuthorizationRole): Boolean = when (role) {
        AuthorizationRole.READ_ONLY -> capabilities.any { it in READ_CAPABILITIES }
        AuthorizationRole.PAYMENT_ONLY -> CAP_INITIATE_PAYMENT in capabilities
        AuthorizationRole.FULL_ACCESS -> capabilities.containsAll(FULL_ACCESS_CAPABILITIES)
        AuthorizationRole.CARD_HOLDER -> false
    }

    fun withinPerTransactionLimit(amount: java.math.BigDecimal, currency: String): Boolean {
        if (perTransactionLimitAmount == null) return true
        if (perTransactionLimitCurrency != currency) return false
        return amount.compareTo(perTransactionLimitAmount) <= 0
    }

    companion object {
        const val CAP_READ_BALANCES = "ACCOUNT_READ_BALANCES"
        const val CAP_READ_TRANSACTIONS = "ACCOUNT_READ_TRANSACTIONS"
        const val CAP_INITIATE_PAYMENT = "ACCOUNT_INITIATE_PAYMENT"

        val READ_CAPABILITIES = setOf(CAP_READ_BALANCES, CAP_READ_TRANSACTIONS)
        val FULL_ACCESS_CAPABILITIES = setOf(CAP_READ_BALANCES, CAP_READ_TRANSACTIONS, CAP_INITIATE_PAYMENT)
    }
}
