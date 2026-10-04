// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.domain.error

import com.openbank.libs.domain.error.ErrorCategory
import com.openbank.libs.domain.error.ErrorCode
import com.openbank.libs.domain.error.ValidationFailure
import com.openbank.libs.domain.error.Violation
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money

/**
 * The SCT Inst bounded context's own error codes (ADR-0326). Published contract: append-only,
 * never renamed, never reused for another meaning.
 */
enum class SctInstErrorCode(override val category: ErrorCategory, override val title: String) : ErrorCode {
    /**
     * The currency is a valid ISO 4217 code but the SCT Inst scheme does not carry it — SCT Inst is
     * euro-only (EPC SCT Inst rulebook). The ISO 20022 equivalent reason is `AM03` NotAllowedCurrency.
     */
    CURRENCY_NOT_ALLOWED(ErrorCategory.VALIDATION, "The currency is not allowed by the payment scheme"),
    ;

    override val code: String get() = name
}

/** Scheme-level rules that apply to a submission once its amount is a valid kernel [Money]. */
object SctInstSchemeRules {
    /** The only currency the SCT Inst scheme settles in. */
    val SCHEME_CURRENCY: CurrencyCode = CurrencyCode.EUR

    private const val CURRENCY_MESSAGE = "SCT Inst accepts EUR only"

    /**
     * #11913: refuses a non-EUR [amount] as a 400 naming the `currency` field. The rejected value is
     * deliberately not echoed (ADR-0326 [Violation]).
     */
    fun requireSchemeCurrency(amount: Money) {
        if (amount.currency != SCHEME_CURRENCY) {
            val code = SctInstErrorCode.CURRENCY_NOT_ALLOWED
            throw ValidationFailure(
                clientMessage = "currency: $CURRENCY_MESSAGE",
                violations = listOf(Violation("currency", CURRENCY_MESSAGE, code.code)),
                errorCode = code,
                internalDetail = "SCT Inst submission refused: currency ${amount.currency.code} is not EUR",
            )
        }
    }
}
