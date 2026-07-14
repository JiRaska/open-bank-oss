// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.interest.application.port.`in`.RemitWithholdingUseCase
import com.openbank.interest.infrastructure.client.InitiateTransactionRequest
import com.openbank.interest.infrastructure.client.TransactionServiceClient
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
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
    // Poison-pill safety: this boundary must swallow ANY failure (parse, rail call) and ack, so
    // one bad event cannot wedge the consumer group.
    @Suppress("TooGenericExceptionCaught")
    suspend fun consume(payload: String) {
        try {
            val node = objectMapper.readTree(payload)
            if (node.path("eventType").asText() != EVENT_WITHHOLDING_REMITTED) return

            val remittanceId = UUID.fromString(node.path("remittanceId").asText())
            val idempotencyKey = "interest-withholding-$remittanceId"
            val request = InitiateTransactionRequest(
                idempotencyKey = idempotencyKey,
                type = "DEBIT",
                sourceAccountId = UUID.fromString(remittanceSourceAccountId),
                amount = node.path("totalTaxAmount").decimalValue(),
                currencyCode = node.path("currency").asText(),
                description = "Withholding tax remittance ${node.path("periodYear").asText()}-" +
                    "${node.path("periodMonth").asText()} / ${node.path("authority").asText()}",
                valueDate = node.path("dueDate").asText(),
                rail = "DOMESTIC",
                instructionType = "ONE_OFF",
            )

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
                    log.errorf(
                        "[withholding-remittance] batch %s FAILED to book (HTTP %d) — a due tax remittance " +
                            "did not move money. This requires manual investigation; the batch stays PENDING " +
                            "for retry (a redelivery of this event, or an operator re-driving it).",
                        remittanceId,
                        response.status,
                    )
                }
            }
        } catch (e: Exception) {
            log.errorf(e, "[withholding-remittance] failed to handle remitted event: %.300s", payload)
        }
    }

    private companion object {
        const val EVENT_WITHHOLDING_REMITTED = "interest.withholding.remitted.v1"
        const val HTTP_CONFLICT = 409
    }
}
