// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.standingorder.application.port.`in`.StandingOrderUseCase
import com.openbank.standingorder.infrastructure.client.CreateSepaPaymentRequest
import com.openbank.standingorder.infrastructure.client.SepaPaymentClient
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.util.UUID

/**
 * Orchestrates the actual execution of a due standing order (#889).
 *
 * The daily sweep ([com.openbank.standingorder.infrastructure.scheduler.StandingOrderExecutionScheduler])
 * writes a transactional-outbox row per due order, dispatched to Kafka as `standing-order.due.v1`.
 * This consumer picks it up and, per `paymentType`, initiates the real payment on the appropriate
 * rail, then reports the outcome back onto the order:
 *
 *  - `SEPA_CREDIT` → POST sepa-payment `/api/v1/sepa-payments` (SCT). 2xx ⇒ [StandingOrderUseCase.confirmExecution];
 *    non-2xx ⇒ [StandingOrderUseCase.recordFailure].
 *  - `DOMESTIC` / `INTERNAL` → not wired yet (the CZK/internal rails need a different request shape than
 *    the order carries). Recorded as a failure with a clear log line rather than silently dropped, and
 *    tracked as a follow-up. Until then only SEPA standing orders actually move money.
 *
 * Idempotency: the payment call reuses the event's deterministic `so-exec-{orderId}-{executionDate}`
 * key, so a Kafka redelivery replays the same payment (sepa-payment returns the cached 201) instead of
 * double-paying. Poison-pill safe: any parse/dispatch error is logged and the message acked so one bad
 * event cannot wedge the consumer group.
 *
 * NOTE: the outbox topic also carries `standing-order.failed.v1` events (no `paymentType` field); those
 * are ignored here — routing keys off the presence of `paymentType`, which only the due event carries.
 */
@ApplicationScoped
class StandingOrderDueConsumer(
    private val useCase: StandingOrderUseCase,
    private val objectMapper: ObjectMapper,
    @RestClient private val sepaClient: SepaPaymentClient,
) {
    private val log = Logger.getLogger(StandingOrderDueConsumer::class.java)

    @Incoming("standing-order-due-in")
    // Poison-pill safety: the consumer boundary must swallow ANY failure (parse, rail call, DB) and
    // ack, so one bad event cannot wedge the consumer group — hence the deliberately broad catch.
    @Suppress("TooGenericExceptionCaught")
    suspend fun consume(payload: String) {
        try {
            val node = objectMapper.readTree(payload)
            // Only the due event carries paymentType; failed events (failureCount/status) are skipped.
            if (node.path("paymentType").isMissingNode) return
            val orderId = runCatching { UUID.fromString(node.path("orderId").asText()) }.getOrNull()
                ?: run {
                    log.warnf("[standing-order-exec] due event without a valid orderId — skipping: %.300s", payload)
                    return
                }
            when (val paymentType = node.path("paymentType").asText()) {
                "SEPA_CREDIT" -> executeSepa(orderId, node)
                else -> {
                    log.warnf(
                        "[standing-order-exec] order %s paymentType %s is not yet wired to a rail (#889 follow-up) " +
                            "— recording failure",
                        orderId,
                        paymentType,
                    )
                    recordFailureSafely(orderId)
                }
            }
        } catch (e: Exception) {
            log.errorf(e, "[standing-order-exec] failed to handle due event: %.300s", payload)
        }
    }

    private suspend fun executeSepa(orderId: UUID, node: com.fasterxml.jackson.databind.JsonNode) {
        val debtorIban = node.path("debtorIban").asText(null)?.takeIf { it.isNotBlank() }
        val debtorName = node.path("debtorName").asText(null)?.takeIf { it.isNotBlank() }
        if (debtorIban == null || debtorName == null) {
            log.warnf(
                "[standing-order-exec] order %s missing debtor IBAN/name — cannot initiate SEPA transfer, recording failure",
                orderId,
            )
            recordFailureSafely(orderId)
            return
        }
        val currency = node.path("currency").asText()
        val request = CreateSepaPaymentRequest(
            type = "SCT",
            debtorAccountId = UUID.fromString(node.path("debitAccountId").asText()),
            debtorIban = debtorIban,
            debtorName = debtorName,
            creditorIban = node.path("creditorIban").asText(),
            creditorName = node.path("creditorName").asText(),
            creditorBic = node.path("creditorBic").asText(null)?.takeIf { it.isNotBlank() },
            amount = toMajorUnits(node.path("amountMinorUnits").asLong(), currency),
            currency = currency,
            remittanceInfo = node.path("remittanceInfo").asText(null)?.takeIf { it.isNotBlank() },
            endToEndId = node.path("idempotencyKey").asText(),
        )
        val idempotencyKey = node.path("idempotencyKey").asText()
        val response = sepaClient.createPayment(idempotencyKey, request).awaitSuspending()
        val status = response.status
        if (response.statusInfo.family == Response.Status.Family.SUCCESSFUL) {
            log.infof("[standing-order-exec] order %s SEPA transfer accepted (HTTP %d)", orderId, status)
            runCatching { useCase.confirmExecution(orderId) }
                .onFailure { log.warnf(it, "[standing-order-exec] confirmExecution failed for order %s", orderId) }
        } else {
            log.warnf(
                "[standing-order-exec] order %s SEPA transfer rejected (HTTP %d) — recording failure",
                orderId,
                status,
            )
            recordFailureSafely(orderId)
        }
    }

    // Minor units (e.g. 220000 "cents") → major units (2200.00) using the currency's fraction digits.
    private fun toMajorUnits(minorUnits: Long, currency: String): BigDecimal =
        BigDecimal.valueOf(minorUnits, CurrencyCode.of(currency).defaultFractionDigits)

    private suspend fun recordFailureSafely(orderId: UUID) {
        runCatching { useCase.recordFailure(orderId) }
            .onFailure { log.warnf(it, "[standing-order-exec] recordFailure failed for order %s", orderId) }
    }
}
