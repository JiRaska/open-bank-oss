// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.domain.model

/**
 * The single answer to "who was on the other side of this transaction", as a lookup key.
 *
 * There are two sources and they do not overlap. A card payment carries an acquirer descriptor in
 * `description` and no counterparty name. A transfer or direct debit carries an ISO 20022
 * creditor/debtor name and a `description` that is a payment reference — `Nájem 09/2026` — which
 * changes every month and is therefore useless as a key. Preferring the name and falling back to
 * the descriptor picks the stable field in both cases.
 *
 * Both go through [MerchantDescriptor.normalise], the merchant catalogue's normaliser, on purpose:
 * an override the customer sets on a shop and that shop's catalogue entry then agree on what the
 * shop is called, so the override actually shadows the catalogue rather than sitting beside it.
 *
 * Two different counterparties whose names normalise identically would share one override. That is
 * possible — two landlords both called Novák — but the key is scoped to a single account, where
 * the customer can see the collision and has one relationship of each kind, so the exposure is one
 * customer's own two rows rather than anything crossing between customers.
 */
object CounterpartyKey {
    /**
     * The key for a transaction, or null when neither field says anything identifying.
     *
     * Null means "cannot be categorised", never the empty key: transactions with an unreadable
     * counterparty must not all collapse onto one override and inherit each other's category.
     */
    fun of(counterpartyName: String?, description: String?): String? =
        MerchantDescriptor.normalise(counterpartyName) ?: MerchantDescriptor.normalise(description)
}
