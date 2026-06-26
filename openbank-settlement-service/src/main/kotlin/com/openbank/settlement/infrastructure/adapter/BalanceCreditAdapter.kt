// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.settlement.infrastructure.adapter

import com.openbank.settlement.application.port.out.CreditPort
import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.infrastructure.client.BalanceRestClient
import com.openbank.settlement.infrastructure.client.MoneyMovementRequest
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.future.await
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.util.UUID

@ApplicationScoped
class BalanceCreditAdapter(
    @RestClient private val balanceClient: BalanceRestClient,
    private val settlementRepository: SettlementRepository,
) : CreditPort {

    private val log = Logger.getLogger(BalanceCreditAdapter::class.java)

    override suspend fun credit(settlementId: UUID) {
        val settlement = requireNotNull(settlementRepository.findById(settlementId)) {
            "Settlement $settlementId not found"
        }
        log.infof(
            "Crediting payee %s for settlement %s (%s %s)",
            settlement.payeeAccountId,
            settlementId,
            settlement.amount,
            settlement.currency,
        )
        balanceClient.credit(
            settlement.payeeAccountId,
            MoneyMovementRequest(
                amount = settlement.amount,
                currency = settlement.currency,
                referenceId = "settlement-credit-$settlementId",
                description = "Settlement credit $settlementId",
            ),
        ).subscribeAsCompletionStage().await()
    }
}
