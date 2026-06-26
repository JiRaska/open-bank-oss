// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.settlement.infrastructure.adapter

import com.openbank.settlement.application.port.out.LedgerPort
import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.infrastructure.client.JournalLineRequest
import com.openbank.settlement.infrastructure.client.LedgerRestClient
import com.openbank.settlement.infrastructure.client.PostJournalRequest
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.future.await
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

@ApplicationScoped
class LedgerBookAdapter(
    @RestClient private val ledgerClient: LedgerRestClient,
    private val settlementRepository: SettlementRepository,
    @ConfigProperty(name = "openbank.settlement.gl.debit-account-id")
    private val glDebitAccountId: UUID,
    @ConfigProperty(name = "openbank.settlement.gl.credit-account-id")
    private val glCreditAccountId: UUID,
    private val clock: Clock,
) : LedgerPort {

    private val log = Logger.getLogger(LedgerBookAdapter::class.java)

    override suspend fun book(settlementId: UUID) {
        val settlement = requireNotNull(settlementRepository.findById(settlementId)) {
            "Settlement $settlementId not found"
        }
        val today = LocalDate.now(clock).toString()
        log.infof("Booking settlement %s to ledger", settlementId)
        ledgerClient.postJournal(
            PostJournalRequest(
                idempotencyKey = "settlement-book-$settlementId",
                transactionId = settlementId,
                entryDate = today,
                valueDate = today,
                description = "Settlement booking $settlementId",
                lines = listOf(
                    JournalLineRequest(
                        glAccountId = glDebitAccountId,
                        side = "DEBIT",
                        amount = settlement.amount,
                        currencyCode = settlement.currency,
                        baseAmount = settlement.amount,
                        baseCurrencyCode = settlement.currency,
                        subAccountId = settlement.payerAccountId,
                    ),
                    JournalLineRequest(
                        glAccountId = glCreditAccountId,
                        side = "CREDIT",
                        amount = settlement.amount,
                        currencyCode = settlement.currency,
                        baseAmount = settlement.amount,
                        baseCurrencyCode = settlement.currency,
                        subAccountId = settlement.payeeAccountId,
                    ),
                ),
            ),
        ).subscribeAsCompletionStage().await()
    }
}
