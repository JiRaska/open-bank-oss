// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.transaction.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.transaction.application.port.`in`.InitiateTransactionCommand
import com.openbank.transaction.application.port.`in`.TransactionUseCase
import com.openbank.transaction.domain.model.TransactionType
import io.smallrye.common.annotation.Blocking
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Consumes [SchemeAcceptedEvent] from the `payment-scheme-accepted` Kafka channel
 * (ADR-0108): when a payment rail receives ACSC from the scheme (pacs.002), it emits this
 * event and transaction-service runs the ADR-0039 PaymentSagaOrchestrator — cover hold →
 * ledger journal → COMPLETED — to close the PROCESSING → COMPLETED leg of the payment.
 *
 * Idempotency: [InitiateTransactionCommand.idempotencyKey] = paymentId so a duplicated
 * or re-delivered message is a no-op (TransactionService deduplicates on idempotency key).
 */
@ApplicationScoped
class SchemeAcceptedConsumer(
    private val transactionUseCase: TransactionUseCase,
    private val objectMapper: ObjectMapper,
) {
    private val log = Logger.getLogger(SchemeAcceptedConsumer::class.java)
    private val systemActor = UUID.fromString("00000000-0000-0000-0000-000000000001")

    companion object {
        private const val PAYLOAD_PREVIEW_LENGTH = 200
    }

    @Incoming("payment-scheme-accepted")
    @Blocking
    fun onSchemeAccepted(payload: String) {
        val event = try {
            objectMapper.readValue(payload, SchemeAcceptedEvent::class.java)
        } catch (ex: com.fasterxml.jackson.core.JacksonException) {
            val preview = payload.take(PAYLOAD_PREVIEW_LENGTH)
            log.errorf(ex, "Failed to deserialise SchemeAcceptedEvent, dropping: %s", preview)
            return
        }
        log.infof(
            "Received SchemeAcceptedEvent: paymentId=%s rail=%s amount=%s %s",
            event.paymentId,
            event.rail,
            event.amount,
            event.currency,
        )
        runBlocking {
            transactionUseCase.initiateTransaction(
                InitiateTransactionCommand(
                    idempotencyKey = event.paymentId.toString(),
                    type = TransactionType.DEBIT,
                    sourceAccountId = event.debtorAccountId,
                    targetAccountId = event.creditorAccountId,
                    amount = event.amount,
                    currencyCode = event.currency,
                    description = "Rail settlement: ${event.rail} payment ${event.paymentId}",
                    valueDate = event.valueDate,
                    initiatedBy = systemActor,
                    originatingPaymentId = event.paymentId,
                    rail = event.rail,
                ),
            )
        }
    }
}
