// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.payment

/**
 * How money physically moved / which scheme carried it (ADR-0103). One of two orthogonal
 * dimensions stamped on a transaction at origination — the other being [InstructionType].
 * Deliberately NOT collapsed with the instruction type (a SEPA payment can be a one-off,
 * a standing order, OR a direct debit). Subscription and merchant category are derived
 * enrichments, not rails, and are intentionally absent here.
 *
 * [UNKNOWN] is the honest value for legacy / unstampable postings — consumers must prefer
 * showing nothing specific over guessing a rail.
 */
enum class PaymentRail {
    /** Card spend (POS / e-commerce), sourced from card authorization. */
    CARD,

    /** SEPA Credit Transfer (standard). */
    SEPA_CT,

    /** SEPA Instant Credit Transfer. */
    SEPA_INST,

    /** International / correspondent-bank wire (SWIFT). */
    SWIFT,

    /** Domestic CZ transfer (BBAN-based, ČNB clearing). */
    DOMESTIC,

    /** Movement between the customer's own accounts at this bank. */
    INTERNAL,

    /** Physical cash deposit / withdrawal. */
    CASH,

    /** Bank-charged fee. */
    FEE,

    /** Credited / debited interest. */
    INTEREST,

    /** Rail not determinable (legacy rows, system postings). */
    UNKNOWN,
}
