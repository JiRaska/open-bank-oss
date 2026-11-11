// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.lending.application.port.out.LendingOutboxMessage
import com.openbank.lending.application.port.out.LoanEventEmitter
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanInstallment
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.domain.money.Money
import io.smallrye.mutiny.Uni
import java.time.Instant

internal const val LOAN_EXPOSURE_FACT_EVENT = "loan.exposure_fact"

/** Lending-owned changes. Report-date FX and debtor classification come from other systems. */
internal sealed interface LoanExposureChange {
    data class Disbursed(val cashConfirmedAt: Instant) : LoanExposureChange
    data class Repaid(val installment: LoanInstallment, val unpaidScheduledPrincipal: Money) : LoanExposureChange
    data class Rescheduled(val unpaidScheduledPrincipal: Money) : LoanExposureChange
    data object Ended : LoanExposureChange
}

private val exposureFactJson = ObjectMapper()

internal fun LoanEventEmitter.emitExposureFact(
    loan: Loan,
    change: LoanExposureChange,
    effectiveAt: Instant,
    knownSynthetic: Boolean = false,
): Uni<Unit> {
    val loanId = loan.id.value
    val eventId = Ids.newId()
    val body = linkedMapOf<String, Any>(
        "eventType" to LOAN_EXPOSURE_FACT_EVENT,
        "eventVersion" to 1,
        "eventId" to eventId.toString(),
        "aggregateType" to "LOAN",
        "aggregateId" to loanId.toString(),
        "loanId" to loanId.toString(),
        "applicationId" to loan.applicationId.value.toString(),
        "partyId" to loan.partyId.toString(),
        "loanStatus" to loan.status.name,
        "currency" to loan.principal.currency.code,
        "originalPrincipal" to loan.principal.amount,
        "effectiveAt" to effectiveAt.toString(),
        "occurredAt" to effectiveAt.toString(),
        "sourceService" to "lending",
    )
    val (kind, reference) = when (change) {
        is LoanExposureChange.Disbursed -> {
            body["cashConfirmedAt"] = change.cashConfirmedAt.toString()
            "DISBURSED" to "disbursement"
        }
        is LoanExposureChange.Repaid -> {
            val installment = change.installment
            body["installmentId"] = installment.id.toString()
            body["principalRepaid"] = installment.principal.amount
            body["interestRepaid"] = installment.interest.amount
            body["unpaidScheduledPrincipal"] = change.unpaidScheduledPrincipal.amount
            "REPAID" to "installment:${installment.id}"
        }
        is LoanExposureChange.Rescheduled -> {
            body["unpaidScheduledPrincipal"] = change.unpaidScheduledPrincipal.amount
            body["scheduleVersion"] = loan.version
            "RESCHEDULED" to "schedule:${loan.version}"
        }
        LoanExposureChange.Ended -> "ENDED" to "status:${loan.status.name}"
    }
    body["kind"] = kind
    body["sourceReference"] = "loan:$loanId:$reference"
    if (knownSynthetic) body["synthetic"] = true
    return emit(
        LendingOutboxMessage(
            eventId = eventId,
            aggregateId = loanId,
            eventType = LOAN_EXPOSURE_FACT_EVENT,
            payload = exposureFactJson.writeValueAsString(body),
            synthetic = knownSynthetic,
        ),
    )
}
