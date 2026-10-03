// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.domain.error

import com.openbank.libs.domain.error.ErrorCategory
import com.openbank.libs.domain.error.ErrorCode
import com.openbank.libs.domain.error.ValidationFailure
import com.openbank.libs.domain.error.Violation
import com.openbank.standingorder.domain.model.PaymentType

/**
 * The standing-order bounded context's own error codes (ADR-0326). Published contract: append-only,
 * never renamed, never reused for another meaning. `CURRENCY_NOT_ALLOWED` carries the same code,
 * category and title as sepa-payment's and sepa-instant's, so a client sees one wire contract.
 */
enum class StandingOrderErrorCode(override val category: ErrorCategory, override val title: String) : ErrorCode {
    /**
     * The currency is not carried by the order's rail — `SEPA_CREDIT` executes as an SCT, which is
     * euro-only (EPC SCT rulebook; ISO 20022 equivalent reason `AM03` NotAllowedCurrency).
     */
    CURRENCY_NOT_ALLOWED(ErrorCategory.VALIDATION, "The currency is not allowed by the payment scheme"),
    ;

    override val code: String get() = name
}

/** Rail-level currency rules checked before a standing order is created or replaced (#11938). */
object SepaCreditSchemeRules {
    /** The only currency a `SEPA_CREDIT` order can execute in. */
    const val SCHEME_CURRENCY = "EUR"

    private const val CURRENCY_MESSAGE = "SEPA_CREDIT accepts EUR only"

    /**
     * Refuses a `SEPA_CREDIT` order in any currency but EUR (case-insensitive) as a 400 naming the
     * `currency` field; the rejected value is deliberately not echoed. `DOMESTIC` and `INTERNAL`
     * are not constrained here.
     */
    fun requireRailCurrency(paymentType: PaymentType, currency: String) {
        if (paymentType == PaymentType.SEPA_CREDIT && !currency.equals(SCHEME_CURRENCY, ignoreCase = true)) {
            val code = StandingOrderErrorCode.CURRENCY_NOT_ALLOWED
            throw ValidationFailure(
                clientMessage = "currency: $CURRENCY_MESSAGE",
                violations = listOf(Violation("currency", CURRENCY_MESSAGE, code.code)),
                errorCode = code,
                internalDetail = "SEPA_CREDIT standing order refused: currency is not EUR",
            )
        }
    }
}
