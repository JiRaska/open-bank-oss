// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.document.application.port.`in`.AnnualFeeLine
import com.openbank.document.application.port.`in`.AnnualFeeSummaryReadyCommand
import com.openbank.document.application.port.`in`.AnnualStatementDeliveryUseCase
import com.openbank.libs.messaging.EventRetry
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.util.UUID

/**
 * Consumes `billing-service`'s billing-outbox `AnnualFeeSummaryReady` event (ADR-0248) — the one
 * template family that still needs an async Kafka trigger, since a PAD Art. 5 annual statement of
 * fees is a duty the bank must push, not a document the customer requests. Mirrors
 * [AccountCreatedConsumer]'s shape exactly: poison-pill safe (an unparseable payload or one missing
 * a required field is logged and skipped, never crashes the consumer) and delegates to an
 * idempotent use case, so a deterministic downstream failure for one account never wedges delivery
 * for every subsequent one.
 *
 * Delivery failures are split by kind (#5698) — see [withBoundedRetry]. A deterministic failure for
 * one account is still acked; a transient one is retried and then rethrown, so it dead-letters
 * instead of vanishing.
 */
@ApplicationScoped
class AnnualFeeSummaryReadyConsumer(
    private val deliveryUseCase: AnnualStatementDeliveryUseCase,
    private val objectMapper: ObjectMapper,
) {
    private val log = Logger.getLogger(AnnualFeeSummaryReadyConsumer::class.java)

    // TooGenericExceptionCaught: deliberate -- any parse failure on an untrusted event payload must
    // be logged and skipped, never crash this consumer (poison-pill safety), regardless of the
    // specific exception Jackson happens to throw for a given malformed input.
    @Suppress("TooGenericExceptionCaught")
    @Incoming("billing-outbox-events-in")
    suspend fun consume(payload: String) {
        val node: JsonNode = try {
            objectMapper.readTree(payload)
        } catch (e: Exception) {
            log.errorf(e, "Unparseable %s event, skipping: %s", EVENT_TYPE, payload.take(PAYLOAD_LOG_CHARS))
            return
        }
        if (node["eventType"]?.asText() != EVENT_TYPE) return

        val cmd = toCommand(node)
        if (cmd == null) {
            log.warnf("%s missing a required field, skipping: %s", EVENT_TYPE, payload.take(PAYLOAD_LOG_CHARS))
            return
        }

        try {
            EventRetry.withRetry(
                log,
                "Annual statement delivery for account ${cmd.accountId} year ${cmd.year}",
                null,
                isRetryable = EventRetry.RETRY_UNLESS_DETERMINISTIC,
            ) {
                deliveryUseCase.deliverAnnualStatement(cmd)
            }
        } catch (e: IllegalStateException) {
            ackDeterministic(e, cmd)
        } catch (e: IllegalArgumentException) {
            ackDeterministic(e, cmd)
        }
        // Anything else has already been retried and is deliberately NOT caught: it propagates so
        // smallrye-kafka can dead-letter the record rather than acking work that never happened.
    }

    /**
     * Ack a failure that is a property of THIS account rather than of the infrastructure — e.g. no
     * PUBLISHED template for the statement. Statement delivery is best-effort event-driven work, so
     * such an event must not throw out of the stream and, under smallrye-kafka's default
     * fail-strategy, wedge delivery for EVERY subsequent account; and a retry or a DLQ cannot help,
     * since the next delivery fails identically. A missed statement is re-triggerable by
     * billing-service re-draining its outbox, not a money error.
     */
    private fun ackDeterministic(e: RuntimeException, cmd: AnnualFeeSummaryReadyCommand) {
        log.errorf(
            e,
            "Annual statement delivery failed deterministically for account %s year %d; skipping (event acked).",
            cmd.accountId,
            cmd.year,
        )
    }

    // ReturnCount: deliberate -- each required field is validated independently (poison-pill
    // safety), and an elvis-return per field reads far more clearly here than folding six
    // conditions into one boolean expression (which detekt's ComplexCondition rejects anyway).
    @Suppress("ReturnCount")
    private fun toCommand(node: JsonNode): AnnualFeeSummaryReadyCommand? {
        val accountId = node["accountId"]?.asText()
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return null
        val partyRef = node["partyRef"]?.asText()?.takeIf { it.isNotBlank() } ?: return null
        val year = node["year"]?.takeIf { it.isNumber }?.asInt() ?: return null
        val currency = node["currency"]?.asText()?.takeIf { it.isNotBlank() } ?: return null
        val totalFees = node["totalFees"]?.asText()?.let { parseAmount(it) } ?: return null
        val feesNode = node["fees"]?.takeIf { it.isArray } ?: return null

        val fees = feesNode.mapNotNull { toFeeLine(it) }
        val interestRate = node["interestRate"]
            ?.takeUnless { it.isNull || it.isMissingNode }
            ?.asText()
            ?.let { parseAmount(it) }

        return AnnualFeeSummaryReadyCommand(
            accountId = accountId,
            partyRef = partyRef,
            year = year,
            currency = currency,
            fees = fees,
            totalFees = totalFees,
            interestRate = interestRate,
        )
    }

    private fun toFeeLine(feeNode: JsonNode): AnnualFeeLine? {
        val name = feeNode["name"]?.asText()?.takeIf { it.isNotBlank() } ?: return null
        val category = feeNode["category"]?.asText() ?: ""
        val amount = feeNode["amount"]?.asText()?.let { parseAmount(it) } ?: return null
        return AnnualFeeLine(name = name, category = category, amount = amount)
    }

    private fun parseAmount(raw: String): BigDecimal? = runCatching { BigDecimal(raw) }.getOrNull()

    private companion object {
        const val EVENT_TYPE = "AnnualFeeSummaryReady"
        const val PAYLOAD_LOG_CHARS = 200
    }
}
