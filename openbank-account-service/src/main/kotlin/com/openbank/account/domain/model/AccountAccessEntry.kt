// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.domain.model

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Where an entry in the effective-access view came from.
 *
 * The distinction is not cosmetic. Two independent stores can each authorise a debit on the same
 * account — [AccountAuthorization] rows granted by an operator, and delegation grants issued by the
 * customer — and [com.openbank.account.application.usecase.AuthorizationService.authorizeDelegatedPayment]
 * consults both. A view that merged them into one anonymous list would tell an owner *that* someone
 * can spend from their account without telling them who to ask to stop it.
 */
enum class AccountAccessSource {
    /** The account holder. Always present, never revocable, no limits. */
    OWNER,

    /** A bank-operated mandate/signatory row (`account_authorizations`), set by the bank. */
    BANK_MANDATE,

    /** A grant the customer issued themselves through delegation-service. */
    CUSTOMER_DELEGATION,
}

/**
 * One party's effective ability to act on an account, as the payment guard would see it.
 *
 * Deliberately a *projection of the guard*, not a second opinion: it is built from the same two
 * stores, with the same active/validity filters, so what the owner reads here and what a debit
 * actually gets are the same fact. A transparency screen that computed access separately would
 * eventually disagree with the guard, and the screen is the half nobody tests against reality.
 *
 * @param canInitiatePayments whether this entry, on its own, permits a debit today. Read-only
 *   mandates and data-only delegations are shown precisely so the owner can see they cannot.
 * @param perTransactionLimit the ceiling this entry is capped at, when it has one. Null means the
 *   entry carries no ceiling of its own — NOT that it is unlimited in every sense, since account
 *   and product limits still apply elsewhere.
 */
data class AccountAccessEntry(
    val partyId: UUID,
    val source: AccountAccessSource,
    val canInitiatePayments: Boolean,
    val capabilities: Set<String>,
    val perTransactionLimit: BigDecimal? = null,
    val perTransactionLimitCurrency: String? = null,
    val validFrom: OffsetDateTime? = null,
    val validTo: OffsetDateTime? = null,
    /** Present for [AccountAccessSource.CUSTOMER_DELEGATION]: the grant the owner can revoke. */
    val grantId: UUID? = null,
)
