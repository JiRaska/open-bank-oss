// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.rest.dto

import com.openbank.libs.domain.error.ValidationFailure
import com.openbank.libs.domain.error.Violation
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money
import com.openbank.sepa.domain.model.SepaPaymentErrorCode
import java.math.BigDecimal

/**
 * Builds the kernel [Money] (#11642) from a request's `amount` + `currency` at the inbound boundary,
 * translating each way the kernel can refuse into a 400 with a specific code (ADR-0326):
 *
 *  - currency not ISO 4217, or without a minor unit -> [SepaPaymentErrorCode.CURRENCY_UNSUPPORTED]
 *  - amount needs rounding to fit the minor unit    -> [SepaPaymentErrorCode.AMOUNT_SCALE_EXCEEDED]
 *  - amount outside the kernel's magnitude bound    -> `VALIDATION_ERROR`
 *
 * The currency is trimmed and upper-cased exactly as the use case did before this boundary
 * existed, so `" eur"` keeps meaning EUR. The rejected value is never echoed back (see [Violation]).
 */
fun inboundMoney(
    amount: BigDecimal,
    currency: String,
    amountField: String = "amount",
    currencyField: String = "currency",
): Money {
    val code = inboundCurrency(currency, currencyField)
    return try {
        Money(amount, code)
    } catch (unfit: IllegalArgumentException) {
        throw amountRefusal(amount, code, amountField, unfit)
    }
}

private fun inboundCurrency(currency: String, field: String): CurrencyCode = try {
    CurrencyCode.of(currency.trim())
} catch (unknown: IllegalArgumentException) {
    throw refusal(
        SepaPaymentErrorCode.CURRENCY_UNSUPPORTED,
        field,
        "must be an ISO 4217 currency code with a minor unit",
        unknown,
    )
}

private fun amountRefusal(amount: BigDecimal, code: CurrencyCode, field: String, cause: IllegalArgumentException) =
    if (amount.stripTrailingZeros().scale() > code.defaultFractionDigits) {
        refusal(
            SepaPaymentErrorCode.AMOUNT_SCALE_EXCEEDED,
            field,
            "must have at most ${code.defaultFractionDigits} decimal places for ${code.code}",
            cause,
        )
    } else {
        ValidationFailure(
            clientMessage = "Amount is out of range",
            violations = listOf(Violation(field = field, message = "is out of range")),
            internalDetail = cause.message,
            cause = cause,
        )
    }

private fun refusal(code: SepaPaymentErrorCode, field: String, message: String, cause: Throwable) = ValidationFailure(
    clientMessage = "$field $message",
    violations = listOf(Violation(field = field, message = message, code = code.code)),
    errorCode = code,
    internalDetail = cause.message,
    cause = cause,
)
