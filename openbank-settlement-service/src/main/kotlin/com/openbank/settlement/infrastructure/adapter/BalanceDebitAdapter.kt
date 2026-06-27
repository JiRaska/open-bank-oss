// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.adapter

import com.openbank.settlement.application.port.out.DebitPort
import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.infrastructure.client.BalanceRestClient
import com.openbank.settlement.infrastructure.client.MoneyMovementRequest
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.future.await
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.util.UUID

@ApplicationScoped
class BalanceDebitAdapter(
    @RestClient private val balanceClient: BalanceRestClient,
    private val settlementRepository: SettlementRepository,
) : DebitPort {

    private val log = Logger.getLogger(BalanceDebitAdapter::class.java)

    override suspend fun debit(settlementId: UUID) {
        val settlement = requireNotNull(settlementRepository.findById(settlementId)) {
            "Settlement $settlementId not found"
        }
        log.infof(
            "Debiting payer %s for settlement %s (%s %s)",
            settlement.payerAccountId,
            settlementId,
            settlement.amount,
            settlement.currency,
        )
        balanceClient.debit(
            settlement.payerAccountId,
            MoneyMovementRequest(
                amount = settlement.amount,
                currency = settlement.currency,
                referenceId = "settlement-debit-$settlementId",
                description = "Settlement debit $settlementId",
            ),
        ).subscribeAsCompletionStage().await()
    }
}
