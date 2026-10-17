// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.messaging.EventRetry
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.tax.application.usecase.IncomeCreditService
import com.openbank.tax.application.usecase.TaxFilingService
import com.openbank.tax.domain.model.FilingPeriod
import com.openbank.tax.domain.model.ObservedRemittance
import com.openbank.tax.domain.returns.ObservedIncomeCredit
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Consumes `openbank.interest.accrual.event` for BOTH tax filings this service owns:
 *
 *  - `interest.withholding.remitted.v1` → the §38d monthly remittance filing (ADR-0180, the
 *    original purpose of this consumer); and
 *  - `interest.withholding.recorded.v1` → the gross interest credit per account and accrual period
 *    (ADR-0033 §F), recorded for the CRS/FATCA information-exchange assembly (ADR-0340 B).
 *
 * Both event types ride the ONE channel (`withholding-remitted-in`) and are dispatched here on the
 * `ce-type` Kafka header. A second `@Incoming` on the same channel would create a competing consumer
 * inside the same consumer group and each handler would silently miss the events living on
 * partitions assigned to the other — header dispatch on the single channel is the safe shape, and
 * it needs no new consumer group, no new DLQ wiring and no gitops change.
 *
 * **Event-type filtering is header-based, and that is not a stylistic choice** (see ADR-0050 N3):
 * the outbox relay carries the type only as the `ce-type` header; the publisher does NOT duplicate
 * `eventType` into the payload, so a payload-field filter would match nothing — forever, silently.
 *
 * Failure handling is per event type and mirrors the §38d rules this class already applied
 * (#5698/#5745): a malformed target event is unretryable — counted and nacked into the configured
 * DLQ, never acked (acking would permanently omit it from a filing); a failed WRITE is retried and
 * RETHROWN so the connector's `failure-strategy: dead-letter-queue` parks the original record.
 * Neither logs nor nack reasons carry the payload.
 */
@ApplicationScoped
class InterestAccrualEventConsumer(
    private val taxFilingService: TaxFilingService,
    private val incomeCreditService: IncomeCreditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) {
    private val log = Logger.getLogger(InterestAccrualEventConsumer::class.java)

    @Incoming("withholding-remitted-in")
    @Suppress("TooGenericExceptionCaught") // decode and storage failures have separate counters
    suspend fun consume(record: ConsumerRecord<String, String>) {
        val eventType = record.headers().lastHeader(OutboxKafkaHeaders.HEADER_EVENT_TYPE)
            ?.let { String(it.value(), StandardCharsets.UTF_8) }
        when (eventType) {
            EVENT_WITHHOLDING_REMITTED -> consumeRemitted(record)
            EVENT_WITHHOLDING_RECORDED -> consumeRecorded(record)
            else -> count(OUTCOME_IGNORED)
        }
    }

    @Suppress("TooGenericExceptionCaught") // decode and storage failures have separate counters
    private suspend fun consumeRemitted(record: ConsumerRecord<String, String>) {
        // A target event that cannot be decoded must remain recoverable. The connector parks the
        // original record on nack; never copy its potentially sensitive value into the exception.
        val remittance = try {
            parseRemittance(objectMapper.readTree(record.value()))
        } catch (_: Exception) {
            count(OUTCOME_MALFORMED)
            log.error("[withholding-filing] malformed remitted event; sending original record to DLQ")
            throw IllegalArgumentException("Malformed withholding remittance event")
        }

        try {
            val recorded = EventRetry.withRetry(log, "§38d remittance observation", remittance.remittanceId) {
                taxFilingService.observe(remittance)
            }
            count(if (recorded) OUTCOME_RECORDED else OUTCOME_DUPLICATE)
        } catch (e: Exception) {
            // Counted BEFORE the rethrow so the `failed` population survives whichever
            // failure-strategy the channel is configured with — today, the explicit DLQ.
            count(OUTCOME_FAILED)
            throw e
        }
    }

    @Suppress("TooGenericExceptionCaught") // decode and storage failures have separate counters
    private suspend fun consumeRecorded(record: ConsumerRecord<String, String>) {
        val credit = try {
            parseRecorded(objectMapper.readTree(record.value()))
        } catch (_: Exception) {
            count(OUTCOME_MALFORMED)
            log.error("[crs-fatca] malformed withholding-recorded event; sending original record to DLQ")
            throw IllegalArgumentException("Malformed withholding recorded event")
        }

        try {
            val recorded = EventRetry.withRetry(log, "CRS/FATCA income-credit observation", credit.withholdingId) {
                incomeCreditService.record(credit)
            }
            count(if (recorded) OUTCOME_RECORDED else OUTCOME_DUPLICATE)
        } catch (e: Exception) {
            count(OUTCOME_FAILED)
            throw e
        }
    }

    private fun parseRemittance(node: JsonNode) = ObservedRemittance(
        remittanceId = UUID.fromString(node.path("remittanceId").asText()),
        period = FilingPeriod(node.path("periodYear").asInt(), node.path("periodMonth").asInt()),
        currency = node.path("currency").asText(),
        totalTaxAmount = decimalOf(node.path("totalTaxAmount")),
        itemCount = node.path("itemCount").asInt(),
        dueDate = LocalDate.parse(node.path("dueDate").asText()),
        observedAt = Instant.now(clock),
    )

    /**
     * Strict decode: amounts are serialised as JSON *strings* by the producer, and a silent
     * fallback to zero on an unparsable value would file a report understating the income
     * (ADR-0340 B — reporting reads the income events, never a recomputed guess).
     */
    private fun parseRecorded(node: JsonNode) = ObservedIncomeCredit(
        withholdingId = UUID.fromString(node.path("withholdingId").asText()),
        capitalizationId = UUID.fromString(node.path("capitalizationId").asText()),
        accountId = UUID.fromString(node.path("accountId").asText()),
        productId = UUID.fromString(node.path("productId").asText()),
        periodFrom = LocalDate.parse(node.path("periodFrom").asText()),
        periodTo = LocalDate.parse(node.path("periodTo").asText()),
        currency = node.path("currency").asText(),
        grossAmount = decimalOf(node.path("grossAmount")),
        netAmount = decimalOf(node.path("netAmount")),
        occurredAt = Instant.parse(node.path("occurredAt").asText()),
        observedAt = Instant.now(clock),
    )

    private fun decimalOf(node: JsonNode): BigDecimal {
        val raw = if (node.isTextual) node.asText() else node.toString()
        return runCatching { BigDecimal(raw) }.getOrElse {
            throw IllegalArgumentException("Unparsable amount in interest accrual event")
        }
    }

    private fun count(outcome: String) {
        meterRegistry.counter("openbank.tax.withholding_events", "outcome", outcome).increment()
    }

    companion object {
        private const val EVENT_WITHHOLDING_REMITTED = "interest.withholding.remitted.v1"
        private const val EVENT_WITHHOLDING_RECORDED = "interest.withholding.recorded.v1"

        private const val OUTCOME_RECORDED = "recorded"
        private const val OUTCOME_DUPLICATE = "duplicate"
        private const val OUTCOME_IGNORED = "ignored_event_type"
        private const val OUTCOME_MALFORMED = "malformed"
        private const val OUTCOME_FAILED = "failed"
    }
}
