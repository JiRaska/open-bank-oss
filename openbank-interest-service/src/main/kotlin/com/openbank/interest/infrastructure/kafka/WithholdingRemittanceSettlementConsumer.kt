// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.interest.application.port.`in`.RemitWithholdingUseCase
import com.openbank.interest.infrastructure.client.InitiateTransactionRequest
import com.openbank.interest.infrastructure.client.TransactionServiceClient
import com.openbank.libs.messaging.EventRetry
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
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
    // Poison-pill safety applies to the PAYLOAD only: an unparseable body, a bad remittanceId or an
    // undecodable amount is acked, because a redelivery fails identically forever. BOOKING the cash
    // leg is not in that class — it debits this bank's operating account to pay the finanční úřad —
    // so a failing booking is retried and rethrown for the connector to dead-letter (#5698).
    //
    // The previous comment claimed this boundary "must swallow ANY failure (parse, rail call) and
    // ack". Under it, a transient transaction-service or DB outage acked a remittance that was never
    // booked: the batch stays PENDING with no re-drive endpoint (the class KDoc says "only SQL out"),
    // the tax is not paid, and the only trace is an ERROR line nothing pages on. This is the third
    // money-path site behind the gate blind spots this PR closes — `settle(`/`initiate(` were not in
    // the verb list, and the state change sits one private helper deep (`bookAndSettle`).
    @Suppress("TooGenericExceptionCaught")
    suspend fun consume(record: ConsumerRecord<String, String>) {
        // ADR-0050 N3: the event type travels ONLY as the ce-type header — the payload has no
        // eventType field (see KafkaInterestOutboxEventPublisher), so filter on the header.
        val eventType = record.headers().lastHeader(OutboxKafkaHeaders.HEADER_EVENT_TYPE)
            ?.let { String(it.value(), StandardCharsets.UTF_8) }
        if (eventType != EVENT_WITHHOLDING_REMITTED) return

        val decoded = try {
            val node = objectMapper.readTree(record.value())
            Decoded(
                node = node,
                remittanceId = UUID.fromString(node.path("remittanceId").asText()),
                totalTaxAmount = decimalOf(node.path("totalTaxAmount")),
                itemCount = node.path("itemCount").asInt(),
            )
        } catch (e: Exception) {
            log.errorf(e, "[withholding-remittance] undecodable remitted event, acking: %.300s", record.value())
            return
        }

        EventRetry.withRetry(log, "withholding remittance settlement", decoded.remittanceId) {
            if (decoded.totalTaxAmount.signum() <= 0) {
                settleNilOrRefuse(decoded.remittanceId, decoded.totalTaxAmount, decoded.itemCount)
            } else {
                bookAndSettle(
                    decoded.remittanceId,
                    debitRequestFor(decoded.node, decoded.remittanceId, decoded.totalTaxAmount),
                )
            }
        }
    }

    /** The four values decoded from the event body; a failure to produce any of them is a poison pill. */
    private data class Decoded(
        val node: JsonNode,
        val remittanceId: UUID,
        val totalTaxAmount: BigDecimal,
        val itemCount: Int,
    )

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

    /** Books the debit and advances the batch to SETTLED; a 409 is an idempotent success (#999). */
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
                // This branch used to log and RETURN, and its own message promised "the batch stays
                // PENDING for retry (a redelivery of this event...)" — which could not happen,
                // because returning normally ACKS the event. There is no redelivery and no re-drive
                // endpoint, so a refused booking meant the tax was never paid and the only trace was
                // an ERROR line nothing pages on. Throwing is what makes that sentence true: the
                // retry above gets its attempts, and the connector then dead-letters (#5698).
                throw WithholdingRemittanceBookingFailedException(
                    "[withholding-remittance] batch $remittanceId FAILED to book (HTTP ${response.status}) — " +
                        "a due tax remittance did not move money",
                )
            }
        }
    }

    /**
     * The producer serializes BigDecimal fields as JSON *strings* (`"totalTaxAmount":"1234.00"`,
     * see `WithholdingRemittanceService.remittedEvent`), and Jackson's `decimalValue()` silently
     * returns `BigDecimal.ZERO` for a TextNode — the original zero-booking bug. Accept both the
     * string and a plain numeric encoding; anything unparsable throws (caught + logged upstream).
     */
    private fun decimalOf(node: JsonNode): BigDecimal =
        if (node.isNumber) node.decimalValue() else BigDecimal(node.asText())

    private companion object {
        const val EVENT_WITHHOLDING_REMITTED = "interest.withholding.remitted.v1"
        const val HTTP_CONFLICT = 409
    }
}

/**
 * transaction-service refused the remittance debit. Rethrown so the connector dead-letters it
 * rather than acking a tax payment that never happened (#5698). A RuntimeException, deliberately
 * not IllegalState/IllegalArgument: [EventRetry.RETRY_UNLESS_DETERMINISTIC] treats those two as
 * non-retryable, and a refused rail call is exactly the transient case worth another attempt.
 */
class WithholdingRemittanceBookingFailedException(message: String) : RuntimeException(message)
