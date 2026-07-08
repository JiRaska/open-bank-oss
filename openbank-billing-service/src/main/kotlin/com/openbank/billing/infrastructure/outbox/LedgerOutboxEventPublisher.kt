// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.outbox

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.billing.application.port.out.BillingAssessmentRepository
import com.openbank.billing.application.port.out.LedgerPostingPort
import com.openbank.billing.domain.FeeJournalCommand
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import jakarta.enterprise.context.ApplicationScoped

/**
 * The billing outbox's [OutboxEventPublisher]: unlike Kafka-publishing services (interest,
 * standing-order), billing's outbox row IS the intent to post a ledger journal, so "publishing"
 * an entry means calling the ledger directly via [LedgerPostingPort] — not emitting an event
 * (ADR-0143 step 2: "the posting is dispatched through the service's own transactional outbox").
 * On success, the assessed fee is marked POSTED with the returned journal id so the DST
 * conservation invariant (phase 2d) and operator queries can see the outcome without joining
 * back to the ledger.
 */
@ApplicationScoped
class LedgerOutboxEventPublisher(
    private val ledger: LedgerPostingPort,
    private val assessments: BillingAssessmentRepository,
) : OutboxEventPublisher {

    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    override suspend fun publish(entry: OutboxEntry) {
        val payload = mapper.readValue(entry.payload, FeePostIntentPayload::class.java)
        val command = FeeJournalCommand(
            idempotencyKey = payload.idempotencyKey,
            cycleId = payload.cycleId,
            accountId = payload.accountId,
            feeId = payload.feeId,
            amount = payload.amount,
            currency = payload.currency,
            description = payload.description,
        )
        val journalId = ledger.post(command)
        assessments.markPosted(payload.idempotencyKey, journalId)
    }
}

/** The `billing.fee.post-intent.v1` outbox payload shape (written by `BillingAssessmentRepositoryImpl`). */
private data class FeePostIntentPayload(
    val schemaVersion: Int,
    val idempotencyKey: String,
    val cycleId: String,
    val accountId: String,
    val feeId: String,
    val amount: java.math.BigDecimal,
    val currency: String,
    val description: String,
)
