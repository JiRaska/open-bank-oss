// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.anacredit.domain.eligibility

import com.openbank.anacredit.domain.model.CounterpartyType
import com.openbank.anacredit.domain.model.CreditExposure
import java.math.BigDecimal

/**
 * Whether a single credit instrument must appear in the AnaCredit credit dataset.
 *
 * [reason] codes on exclusion are stable and audit-facing:
 * - `HOUSEHOLD_OUT_OF_SCOPE` — the debtor is a natural person; AnaCredit covers legal entities only.
 * - `BELOW_THRESHOLD`        — the debtor's total commitment is below the €25 000 reporting threshold.
 * - `NO_EXPOSURE`            — neither a commitment nor a drawn amount exists; nothing to report.
 */
sealed interface Eligibility {
    data object Reportable : Eligibility
    data class Excluded(val reason: String) : Eligibility
}

/**
 * Pure AnaCredit scope + materiality gate. Evaluated per instrument, but the threshold is keyed to
 * the *debtor's total commitment across all their instruments* — so [debtorTotalCommitmentEur] must
 * be the sum the caller has already aggregated for this debtor at the reference date.
 */
object AnaCreditEligibilityPolicy {

    /** ECB AnaCredit reporting threshold: total commitment to a debtor of at least €25 000. */
    val REPORTING_THRESHOLD_EUR: BigDecimal = BigDecimal("25000")

    fun assess(exposure: CreditExposure, debtorTotalCommitmentEur: BigDecimal): Eligibility {
        if (exposure.debtorType == CounterpartyType.NATURAL_PERSON) {
            return Eligibility.Excluded("HOUSEHOLD_OUT_OF_SCOPE")
        }
        val hasExposure = exposure.committedAmount.signum() > 0 || exposure.drawnAmount.signum() > 0
        if (!hasExposure) {
            return Eligibility.Excluded("NO_EXPOSURE")
        }
        if (debtorTotalCommitmentEur < REPORTING_THRESHOLD_EUR) {
            return Eligibility.Excluded("BELOW_THRESHOLD")
        }
        return Eligibility.Reportable
    }
}
