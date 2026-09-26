// SPDX-License-Identifier: Apache-2.0
package com.openbank.lending.application.usecase

import com.openbank.lending.application.port.out.LendingOutboxMessage
import com.openbank.lending.application.port.out.LoanEventEmitter
import com.openbank.lending.application.port.out.ProvisioningRepository
import com.openbank.lending.domain.model.Loan
import com.openbank.libs.domain.money.Money
import io.smallrye.mutiny.Uni
import java.time.LocalDate

/** Enqueue in the loan transaction; never reverse an external ledger before local commit. */
internal fun ProvisioningRepository.releaseAllowance(loan: Loan, events: LoanEventEmitter, asOf: LocalDate): Uni<Unit> =
    findLatestByLoan(loan.id).flatMap { prior ->
        if (prior == null || !prior.expectedCreditLoss.isPositive()) {
            Uni.createFrom().item(Unit)
        } else {
            events.queueAllowance(
                loan,
                "loan:${loan.id.value}:allowance-release:${prior.id}",
                Money.zero(prior.expectedCreditLoss.currency.code).minus(prior.expectedCreditLoss),
                asOf,
            )
        }
    }

/** All interpolated identifiers are service-generated; a reporting key is validated before use. */
internal fun LoanEventEmitter.queueAllowance(
    loan: Loan,
    reference: String,
    amount: Money,
    asOf: LocalDate,
    eventPayload: String? = null,
): Uni<Unit> {
    require(reference.matches(Regex("[A-Za-z0-9:_-]+"))) { "Invalid allowance reference" }
    return emit(
        LendingOutboxMessage(
            aggregateId = loan.id.value,
            eventType = "lending.allowance.posting",
            payload = """{"reference":"$reference","partyId":"${loan.partyId}","loanId":"${loan.id.value}",""" +
                """"amount":${amount.amount.toPlainString()},"currency":"${amount.currency.code}",""" +
                """"accountingDate":"$asOf","eventPayload":${com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    eventPayload,
                )}}""",
        ),
    )
}
