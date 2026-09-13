// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.messaging.EventRetry
import com.openbank.standingorder.application.port.`in`.StandingOrderUseCase
import com.openbank.standingorder.infrastructure.client.AccountServiceClient
import com.openbank.standingorder.infrastructure.client.CreateSepaPaymentRequest
import com.openbank.standingorder.infrastructure.client.InitiateTransactionRequest
import com.openbank.standingorder.infrastructure.client.SepaPaymentClient
import com.openbank.standingorder.infrastructure.client.TransactionServiceClient
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
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
 *  - `DOMESTIC` / `INTERNAL` → the app only ever sends these two, so this used to mean "every real
 *    standing order fails on every due date" (#3931-class defect: accepted, shown ACTIVE, silently
 *    dead). Resolves `creditorIban` via account-service; when it resolves to an account **of the
 *    same party** as the order, that is an own-account move — the one case `domestic-payment`
 *    itself skips AML/sanctions screening for — so it books straight through transaction-service
 *    as a same-day `TRANSFER` ([SettlementScope], #5225). A creditor belonging to a DIFFERENT
 *    party, or an IBAN that resolves to no account at all (genuinely external CZ payment), both
 *    still need `domestic-payment`'s screened / BBAN-clearing paths this change does not build —
 *    recorded as a clear failure, same as before, never a silently unscreened success.
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
    @RestClient private val accountClient: AccountServiceClient,
    @RestClient private val transactionClient: TransactionServiceClient,
    private val clock: Clock,
) {
    private val log = Logger.getLogger(StandingOrderDueConsumer::class.java)

    @Incoming("standing-order-due-in")
    // Poison-pill safety applies to the PAYLOAD only: an unparseable event, or one with no valid
    // orderId, is acked because a redelivery fails identically forever. Executing the order is NOT
    // in that class — it moves real customer money through sepaClient/transactionClient — so a
    // failing execution is retried and rethrown for the connector to dead-letter (#5698). The
    // previous comment claimed the boundary "must swallow ANY failure (parse, rail call, DB) and
    // ack": under it, a transient rail or DB outage acked a due standing order that never executed,
    // and nothing re-drives it — the due event only fires once per schedule.
    @Suppress("TooGenericExceptionCaught")
    suspend fun consume(payload: String) {
        val node = try {
            objectMapper.readTree(payload)
        } catch (e: Exception) {
            log.errorf(e, "[standing-order-exec] unparseable due event, acking: %.300s", payload)
            return
        }
        // Only the due event carries paymentType; failed events (failureCount/status) are skipped.
        if (node.path("paymentType").isMissingNode) return
        val orderId = runCatching { UUID.fromString(node.path("orderId").asText()) }.getOrNull()
            ?: run {
                log.warnf("[standing-order-exec] due event without a valid orderId — skipping: %.300s", payload)
                return
            }
        EventRetry.withRetry(log, "standing-order execution", orderId) {
            when (val paymentType = node.path("paymentType").asText()) {
                "SEPA_CREDIT" -> executeSepa(orderId, node)
                "DOMESTIC", "INTERNAL" -> executeDomesticOrInternal(orderId, node)
                else -> {
                    log.warnf(
                        "[standing-order-exec] order %s has unrecognised paymentType %s — recording failure",
                        orderId,
                        paymentType,
                    )
                    recordFailureSafely(orderId)
                }
            }
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

    /**
     * `DOMESTIC` and `INTERNAL` orders used to fall straight to [recordFailureSafely] — no rail
     * existed for either, so every real standing order (the app only ever sends these two payment
     * types; `SEPA_CREDIT` is unreachable from it) was accepted, shown ACTIVE, and silently failed
     * on every due date until it auto-suspended (#3931-class defect).
     *
     * The fix routes only the case this consumer can handle both correctly AND safely: an
     * **own-account** move, where the resolved creditor belongs to the SAME party as the order.
     * That is exactly `domestic-payment`'s own `OWN_ACCOUNTS` scope — the one case its own
     * screening logic already skips AML/sanctions checks for — so booking it as a bare
     * transaction-service `TRANSFER` (same-day, no rail, no clearing calendar; `SettlementScope`,
     * #5225) carries no screening gap. transaction-service itself has NO screening client of its
     * own, so routing a payment to a DIFFERENT party this way would silently skip the check
     * `domestic-payment` runs for its `INTERNAL_CLIENT` scope — that case still needs the real
     * screened path and is deliberately left recording a failure, not wrongly auto-booked.
     *
     * A creditor IBAN that resolves to no account at all is a genuinely external CZ payment (a
     * different bank's BBAN) needing the CERTIS-clearing `domestic-payment` rail; that needs an
     * IBAN→BBAN conversion this change does not attempt. Both gaps record a clear failure, same as
     * before this fix, rather than a wrong success.
     */
    private suspend fun executeDomesticOrInternal(orderId: UUID, node: com.fasterxml.jackson.databind.JsonNode) {
        val creditorIban = node.path("creditorIban").asText(null)?.takeIf { it.isNotBlank() }
        if (creditorIban == null) {
            log.warnf("[standing-order-exec] order %s has no creditorIban — recording failure", orderId)
            recordFailureSafely(orderId)
            return
        }
        val targetAccountId = resolveOwnAccountCreditor(orderId, node, creditorIban)
        if (targetAccountId == null) {
            recordFailureSafely(orderId)
            return
        }

        val currency = node.path("currency").asText()
        val idempotencyKey = node.path("idempotencyKey").asText()
        val request = InitiateTransactionRequest(
            idempotencyKey = idempotencyKey,
            type = "TRANSFER",
            sourceAccountId = UUID.fromString(node.path("debitAccountId").asText()),
            targetAccountId = targetAccountId,
            amount = toMajorUnits(node.path("amountMinorUnits").asLong(), currency),
            currencyCode = currency,
            description = node.path("remittanceInfo").asText(null)?.takeIf { it.isNotBlank() },
            valueDate = LocalDate.now(clock).toString(),
        )
        val txResponse = transactionClient.initiate(request).awaitSuspending()
        if (txResponse.statusInfo.family == Response.Status.Family.SUCCESSFUL) {
            log.infof("[standing-order-exec] order %s internal transfer accepted (HTTP %d)", orderId, txResponse.status)
            runCatching { useCase.confirmExecution(orderId) }
                .onFailure { log.warnf(it, "[standing-order-exec] confirmExecution failed for order %s", orderId) }
        } else {
            log.warnf(
                "[standing-order-exec] order %s internal transfer rejected (HTTP %d) — recording failure",
                orderId,
                txResponse.status,
            )
            recordFailureSafely(orderId)
        }
    }

    /**
     * Resolves [creditorIban] and returns its accountId ONLY when it belongs to the same party as
     * the order — the safety boundary [executeDomesticOrInternal]'s KDoc explains. Returns null
     * (and logs why) for every other outcome: no account, an unparseable response, or a different
     * party's account. The caller records failure uniformly on null; only the reason differs.
     */
    private suspend fun resolveOwnAccountCreditor(
        orderId: UUID,
        node: com.fasterxml.jackson.databind.JsonNode,
        creditorIban: String,
    ): UUID? {
        val lookup = runCatching { accountClient.getByIban(creditorIban).awaitSuspending() }
        val response = lookup.getOrNull()
        if (response == null || response.statusInfo.family != Response.Status.Family.SUCCESSFUL) {
            log.infof(
                "[standing-order-exec] order %s creditor IBAN does not resolve to an internal account " +
                    "— external CZ clearing rail is not wired yet (#889 follow-up), recording failure",
                orderId,
            )
            return null
        }
        val lookupJson = runCatching { objectMapper.readTree(response.readEntity(String::class.java)) }.getOrNull()
        val targetAccountId = lookupJson?.path("id")?.asText(null)?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        }
        val creditorPartyId = lookupJson?.path("partyId")?.asText(null)
        if (targetAccountId == null || creditorPartyId == null) {
            log.warnf("[standing-order-exec] order %s: could not parse account lookup response", orderId)
            return null
        }
        val orderPartyId = node.path("partyId").asText(null)
        if (orderPartyId == null || creditorPartyId != orderPartyId) {
            log.infof(
                "[standing-order-exec] order %s pays a different party's account — that needs " +
                    "domestic-payment's screened INTERNAL_CLIENT path, not a bare transaction-service " +
                    "TRANSFER (which carries no AML/sanctions check of its own); recording failure",
                orderId,
            )
            return null
        }
        return targetAccountId
    }

    // Minor units (e.g. 220000 "cents") → major units (2200.00) using the currency's fraction digits.
    private fun toMajorUnits(minorUnits: Long, currency: String): BigDecimal =
        BigDecimal.valueOf(minorUnits, CurrencyCode.of(currency).defaultFractionDigits)

    // best-effort: this is the COMPENSATING write on the failure path — it runs while an execution
    // failure is already being handled, so letting it throw would replace the real error with the
    // error of recording it, and the caller would dead-letter on the wrong cause. The order stays
    // due and the next tick re-executes it, so nothing is silently dropped. Stated because
    // runCatching + onFailure is otherwise indistinguishable from the #5698 swallow.
    private suspend fun recordFailureSafely(orderId: UUID) {
        runCatching { useCase.recordFailure(orderId) }
            .onFailure { log.warnf(it, "[standing-order-exec] recordFailure failed for order %s", orderId) }
    }
}
