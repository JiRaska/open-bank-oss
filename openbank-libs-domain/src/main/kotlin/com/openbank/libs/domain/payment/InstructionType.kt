// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.payment

/**
 * How a money movement was instructed (ADR-0103) — orthogonal to [PaymentRail]. The same
 * rail can carry different instruction types (a SEPA_CT may be a [ONE_OFF], a
 * [STANDING_ORDER], or a [DIRECT_DEBIT]). Subscription is NOT here: it is a behavioural
 * property discovered across many transactions, modelled as a derived enrichment.
 */
enum class InstructionType {
    /** A single, customer-initiated payment. */
    ONE_OFF,

    /** A recurring debtor-initiated order (fixed amount/schedule). */
    STANDING_ORDER,

    /** A creditor-initiated pull (SEPA Direct Debit / inkaso). */
    DIRECT_DEBIT,

    /** A one-off scheduled for a future date. */
    FUTURE_DATED,

    /** A bank/system-generated posting (fee, interest, reversal, adjustment). */
    SYSTEM,

    /** Instruction type not determinable (legacy rows). */
    UNKNOWN,
}
