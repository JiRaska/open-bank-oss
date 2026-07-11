// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.money

import com.openbank.libs.domain.money.Money
import java.math.BigDecimal

/**
 * Shared money edge-case fixtures (issue #467). Every money-path service's own tests hand-roll
 * the same boundary values — a large near-overflow amount, the smallest representable unit, a
 * balanced debit/credit pair — independently (e.g. `MoneyTest.kt`'s `"99999999999999.99"` case,
 * `JournalEntryPropertyTest`'s cents-based amount generator). This does NOT provide a
 * `JournalEntry`/`JournalLine` builder: those types live in each service's own domain model
 * (`openbank-ledger-service`, `openbank-transaction-service`, ...), not in a shared library, so a
 * generic builder for them would invert the dependency direction. What's genuinely shared is the
 * `Money` value itself — this fixture set, plus [MoneyArb] for property-based generation.
 */
object MoneyTestData {

    /** The currencies exercised across the fleet's property/unit tests (all 2 fraction digits). */
    val COMMON_CURRENCIES = listOf("CZK", "EUR", "USD", "GBP", "CHF")

    /** The smallest positive representable amount for a 2-fraction-digit currency. */
    fun minPositive(currencyCode: String): Money = Money.of(BigDecimal("0.01"), currencyCode)

    fun minNegative(currencyCode: String): Money = Money.of(BigDecimal("-0.01"), currencyCode)

    /**
     * A large amount just below the 17-digit precision boundary this fleet has already hit in
     * production code (`MoneyTest.kt`'s own `"handles large amounts without overflow"` case).
     */
    fun largePositive(currencyCode: String): Money = Money.of(BigDecimal("99999999999999.99"), currencyCode)

    fun largeNegative(currencyCode: String): Money = Money.of(BigDecimal("-99999999999999.99"), currencyCode)

    fun zero(currencyCode: String): Money = Money.zero(currencyCode)

    /**
     * The boundary set a money-path test should assert against for a given currency: zero, the
     * smallest positive/negative unit, and the largest amount this fleet's own precision ceiling
     * allows in both directions.
     */
    fun boundaryAmounts(currencyCode: String): List<Money> = listOf(
        zero(currencyCode),
        minPositive(currencyCode),
        minNegative(currencyCode),
        largePositive(currencyCode),
        largeNegative(currencyCode),
    )

    /**
     * A same-currency (value, -value) pair — the minimal shape a balanced double-entry leg pair
     * needs. Callers map this into their own service's JournalLine-equivalent debit/credit legs.
     */
    fun balancedPair(amount: String, currencyCode: String): Pair<Money, Money> {
        val positive = Money.of(BigDecimal(amount), currencyCode)
        return positive to -positive
    }
}
