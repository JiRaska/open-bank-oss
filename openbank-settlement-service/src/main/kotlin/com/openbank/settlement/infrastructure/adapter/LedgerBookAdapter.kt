// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.adapter

import com.openbank.settlement.application.port.out.LedgerPort
import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.infrastructure.client.LedgerRestClient
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

    companion object {
        // Settlement booking is a system-initiated posting (Temporal workflow / legacy async
        // path, ADR-0108), not a human/security-context action — mirrors ledger-service's own
        // FxRevaluationService.SYSTEM_USER sentinel pattern, with a distinct UUID so audit trails
        // can tell settlement-service's system postings apart from ledger's internal ones.
        val SYSTEM_USER: UUID = UUID.fromString("00000000-0000-0000-0000-000000005e77")
    }

    override suspend fun book(settlementId: UUID) {
        val settlement = requireNotNull(settlementRepository.findById(settlementId)) {
            "Settlement $settlementId not found"
        }
        val today = LocalDate.now(clock).toString()
        log.infof("Booking settlement %s to ledger", settlementId)
        ledgerClient.postJournal(
            SettlementJournalFactory.build(
                posting = SettlementJournalFactory.Posting(
                    settlementId = settlementId,
                    amount = settlement.amount,
                    currency = settlement.currency,
                    payerAccountId = settlement.payerAccountId,
                    payeeAccountId = settlement.payeeAccountId,
                ),
                glDebitAccountId = glDebitAccountId,
                glCreditAccountId = glCreditAccountId,
                date = today,
                createdBy = SYSTEM_USER,
            ),
        ).subscribeAsCompletionStage().await()
    }
}
