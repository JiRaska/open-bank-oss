// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending.origination

/**
 * Canonical loan-origination lifecycle states (ADR-0211 D1).
 *
 * The transition *graph* over these states is fixed in code and identical for every
 * jurisdiction; a jurisdiction compliance pack (ADR-0212) may only mark optional
 * states as mandatory (e.g. [REFLECTION_PERIOD] for products whose national law
 * grants a pre-contractual reflection wait). The statutory CCD2 withdrawal window
 * is deliberately NOT a state here: it runs post-disbursement as an exit path on the
 * live loan (ADR-0215), not as an origination gate.
 */
enum class OriginationState {
    DRAFT,
    SUBMITTED,
    KYC_PENDING,
    DOCS_REQUIRED,
    ASSESSMENT,
    DECISION_PENDING,
    FOUR_EYES,
    OFFERED,
    AWAITING_SIGNATURE,
    SIGNED,
    REFLECTION_PERIOD,
    READY_TO_DISBURSE,
    DISBURSED,

    /** Customer abandoned the application before disbursement. */
    WITHDRAWN,

    /** Decision engine or four-eyes declined the application. */
    DECLINED,

    /** Offer / document / KYC validity elapsed (time-driven). */
    EXPIRED,
    ;

    val isTerminal: Boolean
        get() = this in TERMINAL

    companion object {
        val TERMINAL: Set<OriginationState> = setOf(DISBURSED, WITHDRAWN, DECLINED, EXPIRED)
    }
}
