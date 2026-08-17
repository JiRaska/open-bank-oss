// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.outbox

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.billing.application.port.out.BillingAssessmentRepository
import com.openbank.billing.application.port.out.LedgerPostingPort
import com.openbank.billing.domain.FeeJournalCommand
import com.openbank.billing.domain.FeeReversalCommand
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import org.apache.kafka.common.header.internals.RecordHeaders
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message

/**
 * The billing outbox's [OutboxEventPublisher]: unlike Kafka-publishing services (interest,
 * standing-order), billing's outbox row IS the intent to post a ledger journal, so "publishing"
 * an entry means calling the ledger directly via [LedgerPostingPort] — not emitting an event
 * (ADR-0143 step 2: "the posting is dispatched through the service's own transactional outbox").
 * On success, the assessed fee is marked POSTED with the returned journal id so the DST
 * conservation invariant (phase 2d) and operator queries can see the outcome without joining
 * back to the ledger.
 *
 * Dispatches on [OutboxEntry.eventType] (ADR-0143 phase 2e): a `billing.fee.reversal-intent.v1`
 * row posts the COMPENSATING journal via [LedgerPostingPort.postReversal] and marks the fee
 * REVERSED, instead of the charge path's `post`/`markPosted`.
 *
 * A third eventType, `billing.annual-fee-summary.ready` (ADR-0248), is neither a charge nor a
 * reversal — it is relayed to Kafka via [emitter] instead of called against the ledger, since
 * document-service (not ledger-service) is the consumer. This is billing-service's FIRST Kafka
 * publisher: every prior outbox row here was "publish" meaning "POST to ledger-service" (this
 * file's own class KDoc above says so explicitly), so [emitter] and the
 * `openbank.billing.annual-fee-summary.scheduler` Kafka channel in `application.yaml` are new,
 * not a reuse of an existing billing Kafka topic — there wasn't one before this ADR.
 */
@ApplicationScoped
class LedgerOutboxEventPublisher(
    private val ledger: LedgerPostingPort,
    private val assessments: BillingAssessmentRepository,
    @Channel("billing-events-out") private val emitter: MutinyEmitter<String>,
) : OutboxEventPublisher {

    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    override suspend fun publish(entry: OutboxEntry) {
        when (entry.eventType) {
            REVERSAL_INTENT_EVENT_TYPE -> publishReversal(entry)
            ANNUAL_FEE_SUMMARY_EVENT_TYPE -> publishAnnualFeeSummary(entry)
            else -> publishCharge(entry)
        }
    }

    private suspend fun publishCharge(entry: OutboxEntry) {
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

    private suspend fun publishReversal(entry: OutboxEntry) {
        val payload = mapper.readValue(entry.payload, FeeReversalIntentPayload::class.java)
        val command = FeeReversalCommand(
            idempotencyKey = payload.idempotencyKey,
            originalIdempotencyKey = payload.originalIdempotencyKey,
            cycleId = payload.cycleId,
            accountId = payload.accountId,
            feeId = payload.feeId,
            amount = payload.amount,
            currency = payload.currency,
            reason = payload.reason,
        )
        val reversalJournalId = ledger.postReversal(command)
        assessments.markReversed(payload.originalIdempotencyKey, reversalJournalId)
    }

    /**
     * Relays the annual-summary row to Kafka as-is (the payload is already the final wire JSON —
     * built by `AnnualFeeSummaryOutboxPayloads` in `BillingAssessmentRepositoryImpl` when the row
     * was appended, ADR-0248). Same addressing convention as every Kafka-publishing outbox in the
     * fleet (`KafkaInterestOutboxEventPublisher`, `KafkaStandingOrderOutboxEventPublisher`):
     * partition key = aggregate id, `ce-id`/`idempotency-key`/`ce-type` headers via
     * [OutboxKafkaHeaders], so an at-least-once consumer can dedupe and downstream ordering is
     * preserved per (accountId, year) aggregate.
     */
    private suspend fun publishAnnualFeeSummary(entry: OutboxEntry) {
        val kafkaHeaders = RecordHeaders()
        OutboxKafkaHeaders.headersFor(entry).forEach { (k, v) -> kafkaHeaders.add(k, v.toByteArray()) }
        val metadata = OutgoingKafkaRecordMetadata.builder<String>()
            .withKey(OutboxKafkaHeaders.partitionKey(entry))
            .withHeaders(kafkaHeaders)
            .build()
        emitter.sendMessage(Message.of(entry.payload).addMetadata(metadata)).awaitSuspending()
    }

    private companion object {
        const val REVERSAL_INTENT_EVENT_TYPE = "billing.fee.reversal-intent.v1"

        /** Mirrors `BillingAssessmentRepositoryImpl.ANNUAL_FEE_SUMMARY_EVENT_TYPE` (ADR-0248). */
        const val ANNUAL_FEE_SUMMARY_EVENT_TYPE = "billing.annual-fee-summary.ready"
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

/** The `billing.fee.reversal-intent.v1` outbox payload shape (written by `BillingAssessmentRepositoryImpl`). */
private data class FeeReversalIntentPayload(
    val schemaVersion: Int,
    val idempotencyKey: String,
    val originalIdempotencyKey: String,
    val cycleId: String,
    val accountId: String,
    val feeId: String,
    val amount: java.math.BigDecimal,
    val currency: String,
    val reason: String,
)
