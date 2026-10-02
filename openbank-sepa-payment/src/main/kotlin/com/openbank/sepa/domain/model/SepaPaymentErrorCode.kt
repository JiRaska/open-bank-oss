// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.domain.model

import com.openbank.libs.domain.error.ErrorCategory
import com.openbank.libs.domain.error.ErrorCode

/**
 * sepa-payment's own error codes (ADR-0326: one enum per bounded context). Append-only: a code is a
 * published contract once shipped, so an entry is never renamed, reused or moved to another category.
 */
enum class SepaPaymentErrorCode(override val category: ErrorCategory, override val title: String) : ErrorCode {
    /** The amount has more fraction digits than the currency's minor unit; it is never rounded. */
    AMOUNT_SCALE_EXCEEDED(ErrorCategory.VALIDATION, "The amount has more decimal places than the currency allows"),

    /** Not an ISO 4217 code, or a code with no minor unit (e.g. XAU), so no amount can be held in it. */
    CURRENCY_UNSUPPORTED(ErrorCategory.VALIDATION, "The currency is not a supported ISO 4217 currency"),
    ;

    override val code: String get() = name
}
