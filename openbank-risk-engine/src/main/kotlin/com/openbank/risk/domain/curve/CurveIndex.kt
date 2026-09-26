// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.curve

/**
 * The closed set of rate indices a curve can be built for — the same six that lending's
 * `LoanRateIndex` and interest-service's configs may float on (ADR-0314 D5), so every floating
 * contract has a curve to project from. [currency] is the currency the index is quoted in.
 */
enum class CurveIndex(val currency: String) {
    CZEONIA("CZK"),
    PRIBOR_1M("CZK"),
    PRIBOR_3M("CZK"),
    PRIBOR_6M("CZK"),
    ESTR("EUR"),
    EURIBOR_3M("EUR"),
    ;

    companion object {
        /**
         * The curve a currency's flows are DISCOUNTED on: the overnight index (CZK → CZEONIA,
         * EUR → €STR). Null for a currency with no curve in the set — the caller must report such
         * flows as unpriced, never discount them on some other currency's curve.
         */
        fun discountingFor(currency: String): CurveIndex? = when (currency) {
            "CZK" -> CZEONIA
            "EUR" -> ESTR
            else -> null
        }
    }
}
