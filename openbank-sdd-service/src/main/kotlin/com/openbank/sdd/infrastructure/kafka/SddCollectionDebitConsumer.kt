// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sdd.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.clients.transaction.TransactionCallOutcome
import com.openbank.clients.transaction.TransactionClient
import com.openbank.clients.transaction.TransactionCompletionUncertainException
import com.openbank.clients.transaction.model.InitiateTransactionRequest
import com.openbank.libs.domain.money.RoundingPolicy
import com.openbank.libs.messaging.EventRetry
import com.openbank.sdd.infrastructure.client.TransactionServiceClient
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.time.LocalDate

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
    private val transactions = TransactionClient(transactionClient)

    @Incoming("sdd-collection-authorised-in")
    // Poison-pill safety applies to the PAYLOAD only: an unparseable event is acked, because a
    // redelivery decodes identically and fails identically forever. The debit itself is NOT in
    // that class — it moves real customer money, so a failing rail call is retried and then
    // rethrown so the connector dead-letters it (#5698). The previous version's comment claimed
    // this boundary "must swallow ANY failure (parse, rail call) and ack": under it, a transient
    // transaction-service outage acked an authorised collection that never debited, with the
    // event gone and no way to re-drive it.
    @Suppress("TooGenericExceptionCaught")
    suspend fun consume(payload: String) {
        val node = try {
            objectMapper.readTree(payload)
        } catch (e: Exception) {
            log.errorf(e, "[sdd-collection-debit] unparseable event, acking: %.300s", payload)
            return
        }
        try {
            if (node.path("eventType").asText() != EVENT_COLLECTION_AUTHORISED) return

            val mandateId = node.path("mandateId").asText()
            val umr = node.path("umr").asText()
            val request = debitRequestFrom(node, mandateId, umr)

            EventRetry.withRetry(log, "SDD collection debit", "$mandateId/$umr") {
                // Only a COMPLETED transaction counts as debited (initiate() throws
                // TransactionCompletionUncertainException for any other 2xx); an answer that
                // proves nothing (5xx, dropped connection) is thrown by TransactionClient untouched, so
                // EventRetry retries it - the idempotency key makes that safe - and it is never reported
                // as "FAILED to debit".
                // Remove provider payloads retained by binding errors before EventRetry logs them.
                // Only completion uncertainty is translated; cancellation and other failures pass through.
                val outcome = transactions.initiate(request)
                    .onFailure(TransactionCompletionUncertainException::class.java).transform { failure ->
                        TransactionCompletionUncertainException(
                            (failure as TransactionCompletionUncertainException).status,
                        )
                    }
                    .awaitSuspending()
                when (outcome) {
                    is TransactionCallOutcome.HttpSuccess -> log.infof(
                        "[sdd-collection-debit] mandate=%s umr=%s debited (HTTP %d, transaction %s)",
                        mandateId,
                        umr,
                        outcome.status,
                        outcome.body?.id,
                    )

                    TransactionCallOutcome.Conflict -> log.infof(
                        "[sdd-collection-debit] mandate=%s umr=%s already booked (409) — idempotent success",
                        mandateId,
                        umr,
                    )

                    // The rail refused an AUTHORISED collection (400/422). Throwing rather than logging is the
                    // whole point: R-transaction/return generation is not built (#1000), so the only way this
                    // reaches a human is the DLQ.
                    is TransactionCallOutcome.Rejected -> throw SddDebitFailedException(
                        "[sdd-collection-debit] mandate=$mandateId umr=$umr REFUSED by transaction-service " +
                            "(HTTP ${outcome.status}) — an authorised collection did not move money",
                    )
                }
            }
        } catch (e: IllegalArgumentException) {
            // Field-shape defects (a missing id, an amount that will not parse) are unretryable:
            // the same bytes fail the same way forever, so ack rather than wedge the partition.
            log.errorf(e, "[sdd-collection-debit] malformed collection-authorised event, acking: %.300s", payload)
        }
    }

    /**
     * Field-shape work only — no I/O. Throws [IllegalArgumentException] on a malformed event
     * (a bad UUID, an unparseable amount), which [consume] treats as the poison pill and acks.
     */
    private fun debitRequestFrom(
        node: com.fasterxml.jackson.databind.JsonNode,
        mandateId: String,
        umr: String,
    ): InitiateTransactionRequest {
        val dueDate = node.path("dueDate").asText()
        val currency = node.path("currency").asText()
        // EUR (SDD's only currency, CollectionAuthorisationPolicy FF05) has 2 fraction digits;
        // transaction-service rejects an amount whose scale exceeds the currency's — normalise
        // defensively (same footgun domestic-payment's SettlementAdapter already hit).
        val fractionDigits = runCatching { java.util.Currency.getInstance(currency).defaultFractionDigits }
            .getOrDefault(2).coerceAtLeast(0)
        return InitiateTransactionRequest(
            idempotencyKey = "so-sdd-$mandateId-$umr-$dueDate",
            type = "DEBIT",
            sourceAccountId = java.util.UUID.fromString(node.path("accountId").asText()),
            amount = node.path("amount").decimalValue().setScale(fractionDigits, RoundingPolicy.LEDGER_POSTING.mode),
            currencyCode = currency,
            description = "SEPA Direct Debit ${node.path(
                "creditorIdentifier",
            ).asText()} / $umr".take(MAX_DESCRIPTION),
            // A non-ISO date throws DateTimeParseException (not the poison-pill IllegalArgumentException):
            // it surfaces for dead-lettering, as the 400 it used to earn from transaction-service did.
            valueDate = LocalDate.parse(dueDate),
            rail = "SEPA_CT",
            instructionType = "DIRECT_DEBIT",
        )
    }

    private companion object {
        const val EVENT_COLLECTION_AUTHORISED = "sdd.collection.authorised.v1"
        const val MAX_DESCRIPTION = 140
    }
}

/** The rail refused an authorised collection. Rethrown so the connector dead-letters it (#5698). */
class SddDebitFailedException(message: String) : RuntimeException(message)
