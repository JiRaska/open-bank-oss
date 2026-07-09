// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.anacredit.domain.report

import com.openbank.anacredit.domain.eligibility.AnaCreditEligibilityPolicy
import com.openbank.anacredit.domain.eligibility.Eligibility
import com.openbank.anacredit.domain.model.CreditExposure
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Builds the AnaCredit credit-dataset return for a reference date from raw credit exposures.
 *
 * The €25 000 threshold is a *per-debtor* test, so the builder first aggregates each debtor's total
 * EUR commitment, then applies [AnaCreditEligibilityPolicy] per instrument, maps the reportable ones
 * to dataset rows, and records an [ExclusionNote] for every instrument that is dropped.
 */
object AnaCreditReturnBuilder {

    fun build(exposures: List<CreditExposure>, referenceDate: LocalDate): AnaCreditReturn {
        val totalCommitmentByDebtor: Map<String, BigDecimal> = exposures
            .groupBy { it.debtorId }
            .mapValues { (_, list) -> list.fold(BigDecimal.ZERO) { acc, e -> acc + e.committedAmountEur } }

        val records = mutableListOf<AnaCreditCreditRecord>()
        val exclusions = mutableListOf<ExclusionNote>()

        for (exposure in exposures) {
            val debtorTotal = totalCommitmentByDebtor[exposure.debtorId] ?: BigDecimal.ZERO
            when (val decision = AnaCreditEligibilityPolicy.assess(exposure, debtorTotal)) {
                is Eligibility.Reportable -> records += AnaCreditMapper.toRecord(exposure, referenceDate)
                is Eligibility.Excluded ->
                    exclusions += ExclusionNote(exposure.instrumentId, exposure.debtorId, decision.reason)
            }
        }

        return AnaCreditReturn(referenceDate, records.toList(), exclusions.toList())
    }
}

/** Maps a reportable [CreditExposure] onto its AnaCredit credit/financial dataset row. */
object AnaCreditMapper {

    fun toRecord(exposure: CreditExposure, referenceDate: LocalDate): AnaCreditCreditRecord = AnaCreditCreditRecord(
        instrumentId = exposure.instrumentId,
        debtorId = exposure.debtorId,
        instrumentType = exposure.instrumentType,
        currency = exposure.currency,
        outstandingNominalAmount = exposure.drawnAmount,
        offBalanceSheetAmount = exposure.offBalanceSheetAmount,
        arrearsAmount = exposure.arrearsAmount,
        defaultStatus = if (exposure.defaulted) "DEFAULT" else "NOT_IN_DEFAULT",
        referenceDate = referenceDate,
    )
}
