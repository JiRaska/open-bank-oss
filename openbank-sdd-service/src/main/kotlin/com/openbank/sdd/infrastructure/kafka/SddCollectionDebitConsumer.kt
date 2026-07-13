// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sdd.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.sdd.infrastructure.client.InitiateTransactionRequest
import com.openbank.sdd.infrastructure.client.TransactionServiceClient
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.math.RoundingMode

/**
 * Books the debtor-side debit for an authorised SEPA Direct Debit collection (#1000).
 *
 * `openbank.sdd.event` carries every mandate lifecycle event on one topic; this consumer filters
 * for `sdd.collection.authorised.v1` (published by [com.openbank.sdd.application.usecase.SddMandateService.authorise]
 * on [com.openbank.sdd.domain.authorise.AuthorisationResult.Accept]) and ignores the rest. That
 * event is self-sufficient — accountId, debtorIban, amount, currency, mandateId, umr — no
 * cross-service lookup is needed to book it, unlike #889's standing-order fix.
 *
 * Idempotency: `so-sdd-{mandateId}-{umr}-{dueDate}` (deterministic per collection) is both the
 * outbound Idempotency-Key implied by transaction-service's own idempotencyKey field and this
 * consumer's dedup key, so a Kafka redelivery replays the same booking instead of double-debiting.
 *
 * **Scope note (deliberate, tracked as follow-up — see issue #1000):** this consumer books the
 * happy-path debit only. It does not yet generate or handle an R-transaction (SDD scheme return —
 * insufficient funds discovered late, mandate disputed post-collection, etc.), and does not
 * persist a per-collection outcome record (no such entity exists yet; [com.openbank.sdd.domain.model.SddMandate]
 * only carries a `lastCollectionDate` high-water mark, stamped at authorisation time). A failed
 * debit is logged at ERROR — paging-worthy, since an authorised collection that fails to debit is
 * a real money-path defect — but is not automatically retried or reversed. Designing the return
 * path is real design work, not a mechanical extension of this consumer.
 */
@ApplicationScoped
class SddCollectionDebitConsumer(
    private val objectMapper: ObjectMapper,
    @RestClient private val transactionClient: TransactionServiceClient,
) {
    private val log = Logger.getLogger(SddCollectionDebitConsumer::class.java)

    @Incoming("sdd-collection-authorised-in")
    // Poison-pill safety: this boundary must swallow ANY failure (parse, rail call) and ack, so
    // one bad event cannot wedge the consumer group.
    @Suppress("TooGenericExceptionCaught")
    suspend fun consume(payload: String) {
        try {
            val node = objectMapper.readTree(payload)
            if (node.path("eventType").asText() != EVENT_COLLECTION_AUTHORISED) return

            val mandateId = node.path("mandateId").asText()
            val umr = node.path("umr").asText()
            val dueDate = node.path("dueDate").asText()
            val currency = node.path("currency").asText()
            val idempotencyKey = "so-sdd-$mandateId-$umr-$dueDate"

            // EUR (SDD's only currency, CollectionAuthorisationPolicy FF05) has 2 fraction digits;
            // transaction-service rejects an amount whose scale exceeds the currency's — normalise
            // defensively (same footgun domestic-payment's SettlementAdapter already hit).
            val fractionDigits = runCatching { java.util.Currency.getInstance(currency).defaultFractionDigits }
                .getOrDefault(2).coerceAtLeast(0)
            val amount = node.path("amount").decimalValue().setScale(fractionDigits, RoundingMode.HALF_UP)

            val request = InitiateTransactionRequest(
                idempotencyKey = idempotencyKey,
                type = "DEBIT",
                sourceAccountId = java.util.UUID.fromString(node.path("accountId").asText()),
                amount = amount,
                currencyCode = currency,
                description = "SEPA Direct Debit ${node.path(
                    "creditorIdentifier",
                ).asText()} / $umr".take(MAX_DESCRIPTION),
                valueDate = dueDate,
                rail = "SEPA_CT",
                instructionType = "DIRECT_DEBIT",
            )

            val response = transactionClient.initiateTransaction(request).awaitSuspending()
            when {
                response.statusInfo.family == Response.Status.Family.SUCCESSFUL -> {
                    log.infof(
                        "[sdd-collection-debit] mandate=%s umr=%s debited (HTTP %d)",
                        mandateId,
                        umr,
                        response.status,
                    )
                }
                response.status == HTTP_CONFLICT -> {
                    log.infof(
                        "[sdd-collection-debit] mandate=%s umr=%s already booked (409) — idempotent success",
                        mandateId,
                        umr,
                    )
                }
                else -> {
                    log.errorf(
                        "[sdd-collection-debit] mandate=%s umr=%s FAILED to debit (HTTP %d) — an authorised " +
                            "collection did not move money. R-transaction/return generation is not yet built " +
                            "(#1000 follow-up); this requires manual investigation.",
                        mandateId,
                        umr,
                        response.status,
                    )
                }
            }
        } catch (e: Exception) {
            log.errorf(e, "[sdd-collection-debit] failed to handle collection-authorised event: %.300s", payload)
        }
    }

    private companion object {
        const val EVENT_COLLECTION_AUTHORISED = "sdd.collection.authorised.v1"
        const val HTTP_CONFLICT = 409
        const val MAX_DESCRIPTION = 140
    }
}
