// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.adapter

import com.openbank.settlement.application.port.out.ReverseCreditPort
import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.infrastructure.client.BalanceRestClient
import com.openbank.settlement.infrastructure.client.MoneyMovementRequest
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.future.await
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Reverses the settlement credit by debiting the payee (issue #6037). Mirror image of
 * [BalanceReverseDebitAdapter]; see that class for why the opposite movement is the reversal.
 *
 * Unlike the debit reversal, this one can be **refused for a reason no retry resolves**:
 * balance-service's `Balance.applyDebit` requires `booked - amount >= overdraftFloor`, so a payee
 * who has already moved the funds out cannot be debited back and the call answers 422. The caller
 * records [com.openbank.settlement.domain.model.SettlementStatus.REVERSAL_FAILED] for that case —
 * the money is genuinely still with the payee, and recovering it is a collections/dispute process,
 * not an API call.
 */
@ApplicationScoped
class BalanceReverseCreditAdapter(
    @RestClient private val balanceClient: BalanceRestClient,
    private val settlementRepository: SettlementRepository,
) : ReverseCreditPort {

    private val log = Logger.getLogger(BalanceReverseCreditAdapter::class.java)

    companion object {
        /** Reference-id namespace; see `SettlementReversalPorts.kt` for why it is not the forward id. */
        fun referenceId(settlementId: UUID) = "settlement-credit-reversal-$settlementId"
    }

    override suspend fun reverseCredit(settlementId: UUID) {
        val settlement = requireNotNull(settlementRepository.findById(settlementId)) {
            "Settlement $settlementId not found"
        }
        log.warnf(
            "Reversing credit: debiting payee %s for settlement %s (%s %s)",
            settlement.payeeAccountId,
            settlementId,
            settlement.amount,
            settlement.currency,
        )
        balanceClient.debit(
            settlement.payeeAccountId,
            MoneyMovementRequest(
                amount = settlement.amount,
                currency = settlement.currency,
                referenceId = referenceId(settlementId),
                description = "Settlement credit reversal $settlementId",
            ),
        ).subscribeAsCompletionStage().await()
    }
}
