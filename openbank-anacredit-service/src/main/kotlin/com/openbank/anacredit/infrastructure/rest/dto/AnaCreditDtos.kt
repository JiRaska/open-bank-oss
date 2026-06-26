// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.anacredit.infrastructure.rest.dto

import com.openbank.anacredit.application.port.`in`.RegisterExposureCommand
import com.openbank.anacredit.domain.model.CounterpartyType
import com.openbank.anacredit.domain.model.CreditExposure
import com.openbank.anacredit.domain.model.InstrumentType
import com.openbank.anacredit.domain.report.AnaCreditCreditRecord
import com.openbank.anacredit.domain.report.AnaCreditReturn
import com.openbank.anacredit.domain.report.ExclusionNote
import java.math.BigDecimal
import java.time.LocalDate

data class RegisterExposureRequest(
    val instrumentId: String,
    val debtorId: String,
    val debtorType: CounterpartyType,
    val instrumentType: InstrumentType = InstrumentType.OVERDRAFT,
    val currency: String,
    val committedAmount: BigDecimal,
    val drawnAmount: BigDecimal,
    val committedAmountEur: BigDecimal,
    val arrearsAmount: BigDecimal = BigDecimal.ZERO,
    val defaulted: Boolean = false,
    val originationDate: LocalDate,
) {
    fun toCommand() = RegisterExposureCommand(
        instrumentId = instrumentId,
        debtorId = debtorId,
        debtorType = debtorType,
        instrumentType = instrumentType,
        currency = currency,
        committedAmount = committedAmount,
        drawnAmount = drawnAmount,
        committedAmountEur = committedAmountEur,
        arrearsAmount = arrearsAmount,
        defaulted = defaulted,
        originationDate = originationDate,
    )
}

data class ExposureResponse(
    val instrumentId: String,
    val debtorId: String,
    val debtorType: CounterpartyType,
    val instrumentType: InstrumentType,
    val currency: String,
    val committedAmount: BigDecimal,
    val drawnAmount: BigDecimal,
    val offBalanceSheetAmount: BigDecimal,
) {
    companion object {
        fun of(e: CreditExposure) = ExposureResponse(
            instrumentId = e.instrumentId,
            debtorId = e.debtorId,
            debtorType = e.debtorType,
            instrumentType = e.instrumentType,
            currency = e.currency,
            committedAmount = e.committedAmount,
            drawnAmount = e.drawnAmount,
            offBalanceSheetAmount = e.offBalanceSheetAmount,
        )
    }
}

data class CreditRecordDto(
    val instrumentId: String,
    val debtorId: String,
    val instrumentType: InstrumentType,
    val currency: String,
    val outstandingNominalAmount: BigDecimal,
    val offBalanceSheetAmount: BigDecimal,
    val arrearsAmount: BigDecimal,
    val defaultStatus: String,
    val referenceDate: LocalDate,
) {
    companion object {
        fun of(r: AnaCreditCreditRecord) = CreditRecordDto(
            instrumentId = r.instrumentId,
            debtorId = r.debtorId,
            instrumentType = r.instrumentType,
            currency = r.currency,
            outstandingNominalAmount = r.outstandingNominalAmount,
            offBalanceSheetAmount = r.offBalanceSheetAmount,
            arrearsAmount = r.arrearsAmount,
            defaultStatus = r.defaultStatus,
            referenceDate = r.referenceDate,
        )
    }
}

data class ExclusionDto(val instrumentId: String, val debtorId: String, val reason: String) {
    companion object {
        fun of(n: ExclusionNote) = ExclusionDto(n.instrumentId, n.debtorId, n.reason)
    }
}

data class AnaCreditReturnResponse(
    val referenceDate: LocalDate,
    val reportableCount: Int,
    val excludedCount: Int,
    val records: List<CreditRecordDto>,
    val exclusions: List<ExclusionDto>,
) {
    companion object {
        fun of(ret: AnaCreditReturn) = AnaCreditReturnResponse(
            referenceDate = ret.referenceDate,
            reportableCount = ret.reportableCount,
            excludedCount = ret.excludedCount,
            records = ret.records.map(CreditRecordDto::of),
            exclusions = ret.exclusions.map(ExclusionDto::of),
        )
    }
}
