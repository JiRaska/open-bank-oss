// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.rest

import com.openbank.risk.domain.model.Instrument
import com.openbank.risk.domain.model.LoanExtension
import com.openbank.risk.domain.model.ModelVersions
import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.SnapshotRun
import com.openbank.risk.domain.model.TieOutMismatch
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** `asOf` is nullable and checked in the resource: an absent field must be a 400, not a 500. */
data class CreateSnapshotRequest(val asOf: String? = null)

data class MismatchDto(
    val glAccountCode: String?,
    val currency: String,
    val ledgerNet: BigDecimal,
    val positionsNet: BigDecimal,
    val difference: BigDecimal,
)

data class SnapshotRunResponse(
    val id: UUID,
    val asOf: String,
    val recordedAt: Instant,
    val inputHash: String,
    val provenance: String,
    val status: String,
    val positionCount: Int,
    val mismatchCount: Int,
    val mismatches: List<MismatchDto>,
    val requestedBy: String?,
    /** ADR-0314 D2; null for a run recorded before versions were stamped. */
    val modelVersions: ModelVersionsDto?,
    val ledgerCutOff: Instant?,
)

data class ModelVersionsDto(
    val engineVersion: String,
    val capitalSetId: String,
    val capitalSetVersion: String,
    val liquiditySetId: String,
    val liquiditySetVersion: String,
    val irrbbShockSetVersion: String,
    val irrbbShockSource: String,
    val minReservesSetId: String,
    val minReservesSetVersion: String,
    val behaviouralModelId: String,
    val behaviouralModelVersion: String,
)

fun ModelVersions.toDto() = ModelVersionsDto(
    engineVersion,
    capitalSetId,
    capitalSetVersion,
    liquiditySetId,
    liquiditySetVersion,
    irrbbShockSetVersion,
    irrbbShockSource,
    minReservesSetId,
    minReservesSetVersion,
    behaviouralModelId,
    behaviouralModelVersion,
)

data class PositionDto(
    val kind: String,
    val glAccountCode: String?,
    val glAccountType: String?,
    val currency: String,
    val subAccountId: UUID?,
    val amount: BigDecimal,
    val instrumentId: String?,
)

data class PositionsResponse(val runId: UUID, val asOf: String, val positions: List<PositionDto>)

data class UntiedResponse(val error: String, val runId: UUID, val mismatches: List<MismatchDto>)

fun TieOutMismatch.toDto() = MismatchDto(glAccountCode, currency, ledgerNet, positionsNet, difference)

fun SnapshotRun.toResponse() = SnapshotRunResponse(
    id = id,
    asOf = asOf.toString(),
    recordedAt = recordedAt,
    inputHash = inputHash,
    provenance = provenance.wire,
    status = status.name,
    positionCount = positionCount,
    mismatchCount = mismatches.size,
    mismatches = mismatches.map { it.toDto() },
    requestedBy = requestedBy,
    modelVersions = modelVersions?.toDto(),
    ledgerCutOff = ledgerCutOff,
)

fun Position.toDto() =
    PositionDto(kind.name, glAccountCode, glAccountType, currency, subAccountId, amount, instrumentId)

data class InstallmentDto(val number: Int, val dueDate: String, val principal: BigDecimal, val interest: BigDecimal)

data class RateTermsDto(
    val rateType: String,
    val currentAnnualRate: BigDecimal?,
    val index: String?,
    val spread: BigDecimal?,
    val resetFrequencyMonths: Int?,
    val nextResetDate: String?,
)

data class LoanTermsDto(
    val method: String,
    val periodsPerYear: Int,
    val remainingPeriods: Int,
    val nextDueDate: String?,
    val remainingInstallments: List<InstallmentDto>,
)

data class InstrumentDto(
    val id: String,
    val kind: String,
    val glAccountCode: String?,
    val currency: String,
    val outstanding: BigDecimal,
    val valueDate: String?,
    val maturityDate: String?,
    val rateTerms: RateTermsDto?,
    val counterpartyRef: String?,
    val ifrs9Stage: String?,
    val loan: LoanTermsDto?,
    val contractNumber: String? = null,
)

data class InstrumentsResponse(val runId: UUID, val asOf: String, val instruments: List<InstrumentDto>)

fun Instrument.toDto() = InstrumentDto(
    id = id,
    kind = kind.name,
    glAccountCode = glAccountCode,
    currency = currency,
    outstanding = outstanding,
    valueDate = valueDate?.toString(),
    maturityDate = maturityDate?.toString(),
    rateTerms = rateTerms?.let {
        RateTermsDto(
            it.rateType.name,
            it.currentAnnualRate,
            it.index?.name,
            it.spread,
            it.resetFrequencyMonths,
            it.nextResetDate?.toString(),
        )
    },
    counterpartyRef = counterpartyRef,
    ifrs9Stage = ifrs9Stage,
    contractNumber = contractNumber,
    loan = (extension as? LoanExtension)?.let { ext ->
        LoanTermsDto(
            method = ext.method.name,
            periodsPerYear = ext.periodsPerYear,
            remainingPeriods = ext.remainingPeriods,
            nextDueDate = ext.nextDueDate?.toString(),
            remainingInstallments = ext.remainingInstallments.map {
                InstallmentDto(it.number, it.dueDate.toString(), it.principal, it.interest)
            },
        )
    },
)
