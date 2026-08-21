// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.interest.application.port.`in`.RemitWithholdingUseCase
import com.openbank.interest.infrastructure.client.InitiateTransactionRequest
import com.openbank.interest.infrastructure.client.TransactionServiceClient
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.delay
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Books the cash leg of an assembled withholding-tax remittance batch to the finanční úřad (#999).
 *
 * `openbank.interest.accrual.event` carries every interest-service domain event on one topic;
 * this consumer self-subscribes via `interest-withholding-remitted-in` (same topic, a second
 * consumer group) and filters for `interest.withholding.remitted.v1` — published by
 * [com.openbank.interest.application.usecase.WithholdingRemittanceService] once a monthly batch is
 * assembled — ignoring every other event type on the topic. This is the same self-consumption
 * shape as standing-order-service's execution consumer (#889/#994): the service that assembles a
 * batch is also the one that drives its settlement, rather than a cross-service choreography.
 *
 * **Event-type filtering is header-based**: the outbox relay carries the event type only as the
 * `ce-type` Kafka header ([OutboxKafkaHeaders], ADR-0003/ADR-0050 N3) — unlike sdd/clearing, the
 * interest publisher does NOT duplicate `eventType` into the JSON payload, so the consumer reads
 * the raw [ConsumerRecord] and matches the header. A record without the header is ignored (it
 * cannot have come from the compliant outbox relay).
 *
 * By design (see [com.openbank.interest.domain.tax.WithholdingRemittanceStatus] KDoc, ADR-0038):
 * interest-service never posts a ledger journal directly — it asks transaction-service to book the
 * debit, exactly like [com.openbank.sdd.infrastructure.kafka.SddCollectionDebitConsumer] (#1000)
 * and domestic-payment's `SettlementAdapter` do for their own external payments.
 *
 * **The debit's source account is a real bank-owned operating account, never a customer account**
 * (unlike SDD/domestic-payment, which debit the customer). [remittanceSourceAccountId] MUST be set
 * to a real account-service account id before this runs in any real environment — the placeholder
 * default below is a deliberately invalid all-zero UUID so an unconfigured deployment fails LOUD
 * (transaction-service 404s the account lookup) rather than silently debiting a wrong or
 * nonexistent account for a real regulatory tax payment. Configure via
 * `openbank.interest.withholding.remittance-source-account-id` (env
 * `OPENBANK_INTEREST_WITHHOLDING_REMITTANCE_SOURCE_ACCOUNT_ID`) once finance/ops has designated
 * the actual operating account this bank pays the finanční úřad from.
 *
 * Idempotency: `interest-withholding-{remittanceId}` is both the outbound Idempotency-Key implied
 * by transaction-service's own idempotencyKey field and this consumer's dedup key, so a Kafka
 * redelivery replays the same booking instead of double-remitting.
 *
 * ## Failure handling — a bad payload and a bad rail call are not the same thing (#5698)
 *
 * A **malformed payload** is unretryable: a redelivery decodes identically and fails identically
 * forever. It is logged and ACKED, and that is the only poison-pill case here.
 *
 * A **failed downstream call** is the opposite — the event is fine, the rail or the database is
 * not, and a due regulatory tax remittance must still be booked once it recovers. This boundary
 * used to swallow that too: the outer catch wrapped the whole of [bookAndSettle], so a transient
 * transaction-service failure was logged and the message ACKED, stranding the batch PENDING
 * forever with the event gone. Nothing re-drives it — there is no endpoint, only SQL. So every
 * downstream call now goes through [withBoundedRetry] and, if it still fails, is RETHROWN, letting
 * the connector dead-letter it into something a human can see. Same shape as kyc-service's
 * `PartyEventConsumer` (#5698/#5699).
 *
 * The idempotency key below is what makes both a retry and a redelivery safe: transaction-service
 * answers 409 for a batch already booked, and this consumer treats that as success.
 *
 * **Scope note (deliberate, tracked as follow-up — see issue #999):** this consumer books the cash
 * leg only. It does NOT assemble or file the statutory §38d *Vyúčtování daně vybírané srážkou* XML
 * with the finanční úřad — that is a separate regulatory-reporting concern (issue #999 flags
 * finrep-service as the candidate owner, still to be confirmed) and must not be inferred as done
 * from this consumer alone.
 */
@ApplicationScoped
class WithholdingRemittanceSettlementConsumer(
    private val objectMapper: ObjectMapper,
    private val remitUseCase: RemitWithholdingUseCase,
    @RestClient private val transactionClient: TransactionServiceClient,
    @ConfigProperty(
        name = "openbank.interest.withholding.remittance-source-account-id",
        defaultValue = "00000000-0000-0000-0000-000000000000",
    )
    private val remittanceSourceAccountId: String,
) {
    private val log = Logger.getLogger(WithholdingRemittanceSettlementConsumer::class.java)

    @Incoming("interest-withholding-remitted-in")
    // Only the DECODE below is allowed to swallow-and-ack (a malformed payload can never succeed on
    // a redelivery). Every downstream call is retried and then rethrown — see the class KDoc.
    @Suppress("TooGenericExceptionCaught")
    suspend fun consume(record: ConsumerRecord<String, String>) {
        // ADR-0050 N3: the event type travels ONLY as the ce-type header — the payload has no
        // eventType field (see KafkaInterestOutboxEventPublisher), so filter on the header.
        val eventType = record.headers().lastHeader(OutboxKafkaHeaders.HEADER_EVENT_TYPE)
            ?.let { String(it.value(), StandardCharsets.UTF_8) }
        if (eventType != EVENT_WITHHOLDING_REMITTED) return

        val batch = try {
            decode(record.value())
        } catch (e: Exception) {
            // Unretryable: log and ack. This is the ONLY swallowed failure in this consumer.
            log.errorf(e, "[withholding-remittance] undecodable remitted event, acking: %.300s", record.value())
            return
        }

        withBoundedRetry(batch.remittanceId) {
            if (batch.totalTaxAmount.signum() <= 0) {
                settleNilOrRefuse(batch.remittanceId, batch.totalTaxAmount, batch.itemCount)
            } else {
                bookAndSettle(
                    batch.remittanceId,
                    debitRequestFor(batch.node, batch.remittanceId, batch.totalTaxAmount),
                )
            }
        }
    }

    /** The fields this consumer needs off the event; throws on anything unparsable (see [decimalOf]). */
    private data class RemittedBatch(
        val node: JsonNode,
        val remittanceId: UUID,
        val totalTaxAmount: BigDecimal,
        val itemCount: Int,
    )

    private fun decode(payload: String): RemittedBatch {
        val node = objectMapper.readTree(payload)
        return RemittedBatch(
            node = node,
            remittanceId = UUID.fromString(node.path("remittanceId").asText()),
            totalTaxAmount = decimalOf(node.path("totalTaxAmount")),
            itemCount = node.path("itemCount").asInt(),
        )
    }

    /**
     * Retry [block] a bounded number of times, then RETHROW so the connector dead-letters.
     *
     * The rethrow is the point. A caught-and-logged failure acks the message, and an acked message
     * that did no work is indistinguishable from one that succeeded — from Kafka, from the consumer
     * lag metric, and from every dashboard built on either. The only trace was an ERROR line nobody
     * alerts on, while a due tax remittance sat PENDING with no event left to re-drive it (#5698).
     */
    @Suppress("TooGenericExceptionCaught") // the retry is deliberately type-agnostic; it rethrows
    private suspend fun withBoundedRetry(remittanceId: UUID, block: suspend () -> Unit) {
        var attempt = 1
        while (true) {
            try {
                block()
                return
            } catch (e: Exception) {
                if (attempt >= MAX_ATTEMPTS) {
                    log.errorf(
                        e,
                        "[withholding-remittance] batch %s failed after %d attempts (%s: %s) — dead-lettering",
                        remittanceId,
                        attempt,
                        e.javaClass.simpleName,
                        e.message,
                    )
                    throw e
                }
                log.warnf(
                    "[withholding-remittance] batch %s failed (attempt %d/%d, %s: %s) — retrying",
                    remittanceId,
                    attempt,
                    MAX_ATTEMPTS,
                    e.javaClass.simpleName,
                    e.message,
                )
                delay(RETRY_BACKOFF_MS * attempt)
                attempt++
            }
        }
    }

    /**
     * Handles a batch whose decoded tax amount is not positive.
     *
     * A zero total is SETTLED without touching the rail, whatever the item count. Zero is not a
     * decode artefact: [decimalOf] throws on anything unparsable, so a zero that reaches here is
     * the producer's actual figure. And a zero total over a non-empty batch is ordinary — tax is
     * assessed in whole CZK (`WithholdingTaxPolicy.TAX_SCALE = 0`, RoundingMode.DOWN per daňový
     * řád), so any gross below 7.00 CZK yields `taxAmount = 0` while still being `WITHHELD`, and
     * `WithholdingRemittancePolicy.isRemittable` does not filter those out. Refusing them wedged
     * the batch PENDING forever — its rows already `REMITTED`, no re-drive endpoint, only SQL out.
     *
     * A NEGATIVE total is refused: `taxableBase` is `gross.max(ZERO)` and the rate is positive, so
     * no policy path can produce one. It means something upstream is genuinely broken, and booking
     * a negative debit would move money the wrong way.
     */
    private suspend fun settleNilOrRefuse(remittanceId: UUID, totalTaxAmount: BigDecimal, itemCount: Int) {
        if (totalTaxAmount.signum() == 0) {
            // Nothing is owed — a 0.00 debit would be meaningless noise on the rail. The batch's
            // rows are already REMITTED; settling keeps the batch consistent with them.
            remitUseCase.settle(remittanceId).awaitSuspending()
            log.infof(
                "[withholding-remittance] batch %s totals zero tax over %d item(s) — settled without booking",
                remittanceId,
                itemCount,
            )
        } else {
            log.errorf(
                "[withholding-remittance] batch %s decoded a NEGATIVE amount %s with itemCount %d — " +
                    "refusing to book it. No WithholdingTaxPolicy path can produce a negative tax, so " +
                    "this is an upstream defect. The batch stays PENDING; investigate the event payload.",
                remittanceId,
                totalTaxAmount,
                itemCount,
            )
        }
    }

    /** The idempotent DEBIT that asks transaction-service to book the batch's cash leg (ADR-0038). */
    private fun debitRequestFor(node: JsonNode, remittanceId: UUID, totalTaxAmount: BigDecimal) =
        InitiateTransactionRequest(
            idempotencyKey = "interest-withholding-$remittanceId",
            type = "DEBIT",
            sourceAccountId = UUID.fromString(remittanceSourceAccountId),
            amount = totalTaxAmount,
            currencyCode = node.path("currency").asText(),
            description = "Withholding tax remittance ${node.path("periodYear").asText()}-" +
                "${node.path("periodMonth").asText()} / ${node.path("authority").asText()}",
            valueDate = node.path("dueDate").asText(),
            rail = "DOMESTIC",
            instructionType = "ONE_OFF",
        )

    /**
     * Books the debit and advances the batch to SETTLED; a 409 is an idempotent success (#999).
     *
     * Any other non-2xx THROWS — a due tax remittance that did not move money must reach the DLQ,
     * not a log line. A 4xx (e.g. the unconfigured all-zero source account 404ing) cannot succeed
     * on retry, but dead-lettering it is still the loud failure this consumer's KDoc asks for, and
     * strictly louder than the silent ack it replaces.
     */
    private suspend fun bookAndSettle(remittanceId: UUID, request: InitiateTransactionRequest) {
        val response = transactionClient.initiateTransaction(request).awaitSuspending()
        when {
            response.statusInfo.family == Response.Status.Family.SUCCESSFUL -> {
                remitUseCase.settle(remittanceId).awaitSuspending()
                log.infof("[withholding-remittance] batch %s settled (HTTP %d)", remittanceId, response.status)
            }
            response.status == HTTP_CONFLICT -> {
                remitUseCase.settle(remittanceId).awaitSuspending()
                log.infof(
                    "[withholding-remittance] batch %s already booked (409) — idempotent settle",
                    remittanceId,
                )
            }
            else -> {
                // Throwing is what makes the retry real. This branch used to log and return, which
                // ACKED the message — so the "stays PENDING for retry (a redelivery of this event)"
                // the old comment promised could never happen: the event was already gone, and no
                // endpoint re-drives a batch. Now the caller retries and then dead-letters (#5698).
                throw RemittanceBookingFailedException(remittanceId, response.status)
            }
        }
    }

    /**
     * The producer serializes BigDecimal fields as JSON *strings* (`"totalTaxAmount":"1234.00"`,
     * see `WithholdingRemittanceService.remittedEvent`), and Jackson's `decimalValue()` silently
     * returns `BigDecimal.ZERO` for a TextNode — the original zero-booking bug. Accept both the
     * string and a plain numeric encoding; anything unparsable throws, which
     * [consume] logs and ACKS — a malformed payload is the one failure a redelivery cannot fix.
     */
    private fun decimalOf(node: JsonNode): BigDecimal =
        if (node.isNumber) node.decimalValue() else BigDecimal(node.asText())

    private companion object {
        const val EVENT_WITHHOLDING_REMITTED = "interest.withholding.remitted.v1"
        const val HTTP_CONFLICT = 409
        const val MAX_ATTEMPTS = 3
        const val RETRY_BACKOFF_MS = 500L
    }
}

/**
 * The rail refused to book a due withholding-tax remittance. Named (rather than a bare
 * RuntimeException) so the retry/dead-letter path is greppable and detekt's TooGenericExceptionThrown
 * does not need suppressing.
 */
class RemittanceBookingFailedException(remittanceId: UUID, status: Int) :
    RuntimeException(
        "[withholding-remittance] batch $remittanceId FAILED to book (HTTP $status) — a due tax " +
            "remittance did not move money; requires manual investigation",
    )
