// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.domain

/**
 * The currencies the ledger's chart can book fee income in (#13226): `FEE_INCOME_ACCOUNT` is mapped
 * to 4003 (CZK, ledger V15) and to its per-currency siblings 4004/4005/4006 (EUR/USD/GBP, ledger V35).
 * Income is booked in the fee's ORIGINAL currency -- billing never converts.
 *
 * The ledger owns the map (ADR-0341 D3); billing keeps this list only to refuse BEFORE it writes an
 * outbox row the ledger would answer 422 on forever. A fee in any other currency is a counted skip,
 * never a zero charge. Adding a currency here is a pair change with a reviewed ledger migration that
 * maps it -- `FeeJournalsUnchangedTest` parses the ledger migrations and fails if the two disagree.
 */
object FeeIncomeCurrencies {
    val MAPPED: Set<String> = setOf("CZK", "EUR", "USD", "GBP")

    fun isMapped(currency: String): Boolean = currency in MAPPED
}
