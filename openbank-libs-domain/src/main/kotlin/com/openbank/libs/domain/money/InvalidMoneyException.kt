// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.money

import com.openbank.libs.domain.error.ErrorCode
import com.openbank.libs.domain.error.PlatformErrorCode

/**
 * Why the kernel refused to build a [Money] or a [CurrencyCode] (ADR-0326). Each reason names the
 * [errorCode] a caller is answered with, so every service tells the failures apart the same way
 * instead of re-deriving them from the message or the input.
 */
enum class InvalidMoneyReason(val errorCode: ErrorCode) {
    /** The amount needs rounding to fit the currency's minor unit (`1.005` EUR). */
    SCALE_EXCEEDED(PlatformErrorCode.AMOUNT_SCALE_EXCEEDED),

    /** Not a three-letter ISO 4217 code, unknown to ISO 4217, or without a minor unit (`XAU`). */
    CURRENCY_UNSUPPORTED(PlatformErrorCode.CURRENCY_UNSUPPORTED),

    /** Outside the kernel's magnitude bound ([Money.MAX_INTEGER_DIGITS] / [Money.MAX_INPUT_SCALE]). */
    AMOUNT_OUT_OF_RANGE(PlatformErrorCode.VALIDATION_ERROR),
}

/**
 * The typed construction failure of [Money] and [CurrencyCode].
 *
 * It is an [IllegalArgumentException] ON PURPOSE: every existing `catch (e: IllegalArgumentException)`
 * and the libs-runtime generic 400 mapping keep seeing exactly what they saw before, and the
 * Throwable [message] is unchanged. libs-runtime registers a narrower mapper for this type that
 * renders the reason's code as an RFC 9457 problem.
 *
 * [clientMessage] is the text a caller may see; it never contains the rejected value. [field] names
 * the request field when the failure was raised at an API boundary ([Money.parseInbound]).
 */
class InvalidMoneyException(
    val reason: InvalidMoneyReason,
    message: String,
    val clientMessage: String,
    val field: String? = null,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause) {

    val errorCode: ErrorCode get() = reason.errorCode

    /** The same failure attributed to a request [field]; message, cause and stack are kept. */
    fun forField(field: String): InvalidMoneyException =
        InvalidMoneyException(reason, message.orEmpty(), clientMessage, field, cause).also {
            it.stackTrace = stackTrace
        }
}
