// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.money

import com.openbank.libs.domain.money.Money
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import java.math.BigDecimal

/**
 * Shared kotest-property generators for [Money] (issue #467). The cents-based-`Long` generation
 * trick — so every generated amount lands on a valid 2-fraction-digit scale — is already
 * duplicated in `openbank-ledger-service`'s `JournalEntryPropertyTest` and `openbank-libs-domain`'s
 * own `MoneyPropertyTest`; this is the shared version for any *new* property suite.
 */
object MoneyArb {

    private const val DEFAULT_CENTS_BOUND = 999_999_999L
    private val DEFAULT_CENTS_RANGE = -DEFAULT_CENTS_BOUND..DEFAULT_CENTS_BOUND

    /** A generated [Money] in the given currency, amounts in cents so the scale is always valid. */
    fun money(currencyCode: String, centsRange: LongRange = DEFAULT_CENTS_RANGE): Arb<Money> =
        Arb.long(centsRange.first, centsRange.last).map { Money.of(BigDecimal(it).movePointLeft(2), currencyCode) }

    /** One of [MoneyTestData.COMMON_CURRENCIES], for tests that need to vary currency too. */
    fun currency(): Arb<String> = Arb.element(MoneyTestData.COMMON_CURRENCIES)
}
