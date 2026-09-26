// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.decision

import java.math.BigDecimal

/**
 * Closed attribute vocabulary for credit policy rules (ADR-0213 D1). Deliberately
 * finite — a policy that needs an attribute not listed here extends the vocabulary in
 * code (a reviewed change), it does not smuggle it in as free text. Bureau and
 * external scoring inputs arrive only via the ADR-0028 D4 port; their absence makes
 * the dependent rule fail closed to REFER, never to APPROVE.
 */
enum class PolicyAttribute {
    VERIFIED_INCOME_MONTHLY,
    EXISTING_DEBT_SERVICE_MONTHLY,
    DTI,
    DSTI,
    AGE_YEARS,
    EMPLOYMENT_TENURE_MONTHS,
    REQUESTED_AMOUNT,
    TERM_MONTHS,
    BUREAU_SCORE_BAND,
    RESIDENCY,
    CUSTOMER_TYPE,
    PRODUCT_TYPE,
    JURISDICTION,
    CHANNEL,
}

/** Closed operator vocabulary (ADR-0138 comparison set + set membership). */
enum class PolicyOperator { GT, GTE, LT, LTE, EQ, NEQ, IN, NOT_IN }

/** A typed input fact supplied by the caller; the evaluator persists nothing. */
sealed interface PolicyValue {
    data class Numeric(val value: BigDecimal) : PolicyValue

    data class Text(val value: String) : PolicyValue

    fun render(): String = when (this) {
        is Numeric -> value.stripTrailingZeros().toPlainString()
        is Text -> value
    }
}
