// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.messaging

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.`in`.PaymentOutcomeResult
import com.openbank.delegation.application.port.`in`.SettleSpendOnPaymentOutcomeUseCase
import com.openbank.delegation.domain.model.DomesticPaymentOutcome
import com.openbank.libs.messaging.EventRetry
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * ADR-0249 D3 (amended, #16000): settles domestic-payment spend reservations from the payment's
 * OUTCOME on `openbank.domestic.payment.events`, instead of from the rail having answered 2xx.
 *
 * Dispatch is on the outbox `ce-type` header ONLY, compared to literals. The body's own
 * `eventType` is a different audience's discriminator (audit) and is never consulted, so a body
 * cannot promote itself into a settlement.
 *
 * | ce-type / newStatus                         | reservation effect                          |
 * |---------------------------------------------|---------------------------------------------|
 * | status-changed / SETTLED                    | RESERVED -> CONFIRMED                       |
 * | status-changed / REJECTED, CANCELLED        | RESERVED -> RELEASED                        |
 * | status-changed / RETURNED                   | RESERVED or CONFIRMED -> RELEASED           |
 * | delegated-spend.finalized-absent            | RESERVED -> RELEASED                        |
 * | created, held, non-terminal status-changed  | none (`ignored`)                            |
 * | anything else, or no header                 | none (`unrecognised`, alerted)              |
 *
 * Failure rule: a state conflict (out-of-order or stale outcome), an unknown reservation and an
 * event without a reservation are ACKED and counted — redelivering them cannot change the answer,
 * and rethrowing would wedge the partition behind them. A recognised event whose body cannot be
 * read is `unrecognised` and acked as well (a poison pill fails identically forever); the alert is
 * what surfaces it. Only WRITING the settlement is retried and then rethrown, so the channel's
 * `failure-strategy: dead-letter-queue` parks a record whose effect was never recorded.
 *
 * The channel exists only while `openbank.delegation.payment-outcome-consumer.enabled` is true
 * (`mp.messaging.incoming.domestic-payment-events-in.enabled` is bound to it).
 */
@ApplicationScoped
class DomesticPaymentOutcomeConsumer(
    private val settlements: SettleSpendOnPaymentOutcomeUseCase,
    private val objectMapper: ObjectMapper,
    private val meters: MeterRegistry,
) {
    private val log = Logger.getLogger(DomesticPaymentOutcomeConsumer::class.java)

    init {
        // Registered at construction so a cold pod exposes 0 for every outcome rather than no
        // series: `increase()` over a counter born at 1 would miss the first unrecognised event.
        Outcome.entries.forEach { meters.counter(METRIC, TAG, it.label) }
    }

    @Incoming(CHANNEL)
    suspend fun consume(record: ConsumerRecord<String, String>) {
        val type = record.headers().lastHeader(OutboxKafkaHeaders.HEADER_EVENT_TYPE)
            ?.let { String(it.value(), StandardCharsets.UTF_8) }
        val outcome = when (type) {
            STATUS_CHANGED -> handleStatusChanged(record.value())
            FINALIZED_ABSENT -> handleFinalizedAbsent(record.value())
            CREATED, HELD -> Outcome.IGNORED
            else -> {
                log.warnf("[delegation-payment-outcome] unrecognised ce-type %s, acking", type)
                Outcome.UNRECOGNISED
            }
        }
        count(outcome)
    }

    private suspend fun handleStatusChanged(body: String?): Outcome {
        val node = read(body) ?: return Outcome.UNRECOGNISED
        val outcome = when (node.path("newStatus").asText()) {
            "SETTLED" -> DomesticPaymentOutcome.SETTLED
            "REJECTED" -> DomesticPaymentOutcome.REJECTED
            "CANCELLED" -> DomesticPaymentOutcome.CANCELLED
            "RETURNED" -> DomesticPaymentOutcome.RETURNED
            else -> return Outcome.IGNORED
        }
        return settle(node, outcome)
    }

    private suspend fun handleFinalizedAbsent(body: String?): Outcome {
        val node = read(body) ?: return Outcome.UNRECOGNISED
        return settle(node, DomesticPaymentOutcome.FINALIZED_ABSENT)
    }

    private suspend fun settle(node: JsonNode, outcome: DomesticPaymentOutcome): Outcome {
        val reservationId = uuid(node, "reservationId")
        val delegationId = uuid(node, "delegationId")
        if (reservationId == null || delegationId == null) {
            // An owner-initiated payment: nothing was reserved, nothing to settle.
            return Outcome.IGNORED
        }
        val result = EventRetry.withRetry(
            log,
            "delegated spend settlement from payment outcome",
            reservationId,
            isRetryable = EventRetry.RETRY_UNLESS_DETERMINISTIC,
        ) {
            settlements.applyPaymentOutcome(delegationId, reservationId, outcome)
        }
        if (result == PaymentOutcomeResult.CONFLICT || result == PaymentOutcomeResult.UNKNOWN_RESERVATION) {
            log.warnf(
                "[delegation-payment-outcome] %s for reservation %s under delegation %s: %s, acking",
                outcome,
                reservationId,
                delegationId,
                result,
            )
        }
        return when (result) {
            PaymentOutcomeResult.CONFIRMED -> Outcome.CONFIRMED
            PaymentOutcomeResult.RELEASED -> Outcome.RELEASED
            PaymentOutcomeResult.RETURNED_RELEASED -> Outcome.RETURNED_RELEASED
            PaymentOutcomeResult.ALREADY_SETTLED -> Outcome.ALREADY_SETTLED
            PaymentOutcomeResult.CONFLICT -> Outcome.CONFLICT
            PaymentOutcomeResult.UNKNOWN_RESERVATION -> Outcome.UNKNOWN_RESERVATION
        }
    }

    private fun read(body: String?): JsonNode? = try {
        body?.let { objectMapper.readTree(it) }?.takeIf { it.isObject }
    } catch (e: JsonProcessingException) {
        log.errorf(e, "[delegation-payment-outcome] undecodable payment event, acking")
        null
    }

    private fun uuid(node: JsonNode, field: String): UUID? = node.path(field)
        .takeIf { it.isTextual }
        ?.let { runCatching { UUID.fromString(it.asText()) }.getOrNull() }

    private fun count(outcome: Outcome) = meters.counter(METRIC, TAG, outcome.label).increment()

    enum class Outcome(val label: String) {
        CONFIRMED("confirmed"),
        RELEASED("released"),
        RETURNED_RELEASED("returned_released"),
        ALREADY_SETTLED("already_settled"),
        IGNORED("ignored"),
        CONFLICT("conflict"),
        UNKNOWN_RESERVATION("unknown_reservation"),
        UNRECOGNISED("unrecognised"),
    }

    companion object {
        const val CHANNEL = "domestic-payment-events-in"
        const val METRIC = "openbank.delegation.payment_outcome.events"
        private const val TAG = "outcome"

        // Literal `ce-type` values as domestic-payment writes them to its outbox.
        const val STATUS_CHANGED = "domestic.payment.status-changed"
        const val FINALIZED_ABSENT = "domestic.delegated-spend.finalized-absent"
        const val CREATED = "domestic.payment.created"
        const val HELD = "domestic.payment.held"
    }
}
