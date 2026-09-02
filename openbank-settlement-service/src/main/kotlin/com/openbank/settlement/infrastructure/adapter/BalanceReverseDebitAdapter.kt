// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.adapter

import com.openbank.settlement.application.port.out.ReverseDebitPort
import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.infrastructure.client.BalanceRestClient
import com.openbank.settlement.infrastructure.client.MoneyMovementRequest
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.future.await
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Reverses the settlement debit by crediting the payer back (issue #6037).
 *
 * balance-service exposes no `/reverse` endpoint — its full path list is `{accountId}`,
 * `{accountId}/{currency}`, `overdraft-limit`, `holds`, `holds/{holdId}`, `credit`, `debit`,
 * `initialize`, `reconciliation`, `reconciliation/latest`, `approvals/{id}`. The only "undo"
 * primitives it offers are releasing a hold and issuing the opposite movement. Settlement moves
 * money with `debit`/`credit`, not holds, so the opposite movement is the reversal. This mirrors
 * [BalanceDebitAdapter] exactly — same client, same request DTO, same account resolution — with
 * the verb and the reference-id namespace flipped.
 */
@ApplicationScoped
class BalanceReverseDebitAdapter(
    @RestClient private val balanceClient: BalanceRestClient,
    private val settlementRepository: SettlementRepository,
) : ReverseDebitPort {

    private val log = Logger.getLogger(BalanceReverseDebitAdapter::class.java)

    companion object {
        /** Reference-id namespace; see `SettlementReversalPorts.kt` for why it is not the forward id. */
        fun referenceId(settlementId: UUID) = "settlement-debit-reversal-$settlementId"
    }

    override suspend fun reverseDebit(settlementId: UUID) {
        val settlement = requireNotNull(settlementRepository.findById(settlementId)) {
            "Settlement $settlementId not found"
        }
        log.warnf(
            "Reversing debit: crediting payer %s back for settlement %s (%s %s)",
            settlement.payerAccountId,
            settlementId,
            settlement.amount,
            settlement.currency,
        )
        balanceClient.credit(
            settlement.payerAccountId,
            MoneyMovementRequest(
                amount = settlement.amount,
                currency = settlement.currency,
                referenceId = referenceId(settlementId),
                description = "Settlement debit reversal $settlementId",
            ),
        ).subscribeAsCompletionStage().await()
    }
}
