// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.sdd.domain.refund

import com.openbank.sdd.domain.model.SddScheme
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Whether a refund is owed as an unconditional right or as an unauthorised-transaction remedy. */
enum class RefundKind { UNCONDITIONAL, UNAUTHORISED }

/** Outcome of assessing a post-settlement refund claim (ADR-0036 §D). */
sealed interface RefundDecision {
    data class Eligible(val kind: RefundKind, val reasonCode: String) : RefundDecision
    data class Ineligible(val reason: String) : RefundDecision
}

/**
 * Pure refund-window policy (ADR-0036 §D) keyed off the debit date and scheme:
 *  - **Unauthorised** (no/invalid mandate): refundable for **13 months** regardless of scheme
 *    (PSD2 Art. 73/77 / CZ §177).
 *  - **Authorised Core**: **unconditional** refund within **8 weeks** (56 days); beyond that, none.
 *  - **Authorised B2B**: no post-settlement refund (the pre-debit rejection was the only control).
 */
object RefundPolicy {

    /** 8 weeks, expressed in days. */
    const val CORE_UNCONDITIONAL_DAYS = 56L

    /** Statutory unauthorised-transaction window. */
    const val UNAUTHORISED_MONTHS = 13L

    fun assess(
        scheme: SddScheme,
        debitDate: LocalDate,
        asOf: LocalDate,
        authorised: Boolean,
    ): RefundDecision {
        if (asOf.isBefore(debitDate)) {
            return RefundDecision.Ineligible("Debit date $debitDate is in the future relative to $asOf")
        }
        if (!authorised) {
            return if (!asOf.isAfter(debitDate.plusMonths(UNAUTHORISED_MONTHS))) {
                RefundDecision.Eligible(RefundKind.UNAUTHORISED, "MD06")
            } else {
                RefundDecision.Ineligible("Beyond the 13-month unauthorised-transaction window")
            }
        }
        return when (scheme) {
            SddScheme.CORE -> {
                val days = ChronoUnit.DAYS.between(debitDate, asOf)
                if (days <= CORE_UNCONDITIONAL_DAYS) {
                    RefundDecision.Eligible(RefundKind.UNCONDITIONAL, "MD06")
                } else {
                    RefundDecision.Ineligible("Beyond the 8-week unconditional window for an authorised Core collection")
                }
            }
            SddScheme.B2B -> RefundDecision.Ineligible("Authorised B2B collection carries no post-settlement refund right")
        }
    }
}
