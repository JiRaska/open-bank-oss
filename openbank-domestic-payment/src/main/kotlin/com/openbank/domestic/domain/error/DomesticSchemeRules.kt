// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.domain.error

import com.openbank.libs.domain.error.ErrorCategory
import com.openbank.libs.domain.error.ErrorCode
import com.openbank.libs.domain.error.ValidationFailure
import com.openbank.libs.domain.error.Violation
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money

/** Append-only scheme error vocabulary for Czech domestic payments. */
enum class DomesticSchemeErrorCode(override val category: ErrorCategory, override val title: String) : ErrorCode {
    CURRENCY_NOT_ALLOWED(ErrorCategory.VALIDATION, "The currency is not allowed by the payment scheme"),
    ;

    override val code: String get() = name
}

/** Rules applied at submission before an Idempotency-Key is reserved or any payment is persisted. */
object DomesticSchemeRules {
    val SCHEME_CURRENCY: CurrencyCode = CurrencyCode.CZK

    private const val CURRENCY_MESSAGE = "Czech domestic payments accept CZK only"

    fun requireSchemeCurrency(amount: Money): Money {
        if (amount.currency != SCHEME_CURRENCY) {
            val code = DomesticSchemeErrorCode.CURRENCY_NOT_ALLOWED
            throw ValidationFailure(
                clientMessage = "currency: $CURRENCY_MESSAGE",
                violations = listOf(Violation("currency", CURRENCY_MESSAGE, code.code)),
                errorCode = code,
                internalDetail = "Domestic payment submission refused: currency ${amount.currency.code} is not CZK",
            )
        }
        return amount
    }
}
