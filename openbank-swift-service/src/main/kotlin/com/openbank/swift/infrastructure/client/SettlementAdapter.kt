// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.client

import com.openbank.clients.transaction.model.InitiateTransactionRequest
import com.openbank.swift.application.port.out.SettlementOutcome
import com.openbank.swift.application.port.out.SettlementPort
import com.openbank.swift.application.port.out.SettlementUnavailableException
import com.openbank.swift.domain.model.SwiftMessage
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Books settled MT103 funds via transaction-service (ADR-0108).
 * Called after the clearing-simulator returns ACSC and swift-service transitions to SENT.
 * Idempotent: uses `swift-settlement-<messageId>` so retries are safe.
 */
@ApplicationScoped
class SettlementAdapter(@RestClient private val client: TransactionServiceClient) : SettlementPort {

    private val log = Logger.getLogger(SettlementAdapter::class.java)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun settle(message: SwiftMessage): SettlementOutcome {
        val sourceAccountId = message.orderingCustomerAccountId
            ?: return SettlementOutcome(settled = false, transactionId = null).also {
                log.warnf(
                    "No orderingCustomerAccountId for SWIFT message %s — skipping settlement booking",
                    message.id,
                )
            }

        val request = InitiateTransactionRequest(
            idempotencyKey = "swift-settlement-${message.id}",
            type = "DEBIT",
            sourceAccountId = sourceAccountId,
            amount = message.amount.amount,
            currencyCode = message.currency,
            description = "MT103 ${message.remittanceInfo ?: message.transactionReference}",
            // SwiftMessage.valueDate is MT103 basic ISO (YYYYMMDD); transaction-service parses ISO-8601
            // (LocalDate.parse(request.valueDate)), so the raw value was never accepted. The generated
            // client types the field as a date, which forces the conversion to happen here.
            valueDate = LocalDate.parse(message.valueDate, DateTimeFormatter.BASIC_ISO_DATE),
            rail = "SWIFT",
            instructionType = "ONE_OFF",
        )

        return try {
            val response = client.initiateTransaction(request).awaitSuspending()
            SettlementOutcome(settled = true, transactionId = response?.id)
        } catch (ex: WebApplicationException) {
            // The REST client throws on a non-2xx answer: transaction-service WAS reached and refused.
            log.warnf(
                "transaction-service returned HTTP %d for SWIFT message %s settlement; message stays SENT",
                ex.response.status,
                message.id,
            )
            SettlementOutcome(settled = false, transactionId = null)
        } catch (ex: Exception) {
            throw SettlementUnavailableException(
                "transaction-service unreachable for SWIFT message ${message.id}",
                ex,
            )
        }
    }
}
