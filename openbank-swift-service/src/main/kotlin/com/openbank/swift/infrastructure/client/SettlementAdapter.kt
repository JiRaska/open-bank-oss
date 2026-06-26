// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.swift.infrastructure.client

import com.openbank.swift.application.port.out.SettlementOutcome
import com.openbank.swift.application.port.out.SettlementPort
import com.openbank.swift.application.port.out.SettlementUnavailableException
import com.openbank.swift.domain.model.SwiftMessage
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.util.UUID

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

        val request = InitiateSettlementRequest(
            idempotencyKey = "swift-settlement-${message.id}",
            type = "DEBIT",
            sourceAccountId = sourceAccountId,
            amount = BigDecimal.valueOf(message.amountMinorUnits).movePointLeft(MINOR_UNIT_SCALE),
            currencyCode = message.currency,
            description = "MT103 ${message.remittanceInfo ?: message.transactionReference}",
            valueDate = message.valueDate,
            rail = "SWIFT",
            instructionType = "ONE_OFF",
        )

        return try {
            val response: Response = client.initiateTransaction(request).awaitSuspending()
            if (response.status in ACCEPTED_STATUSES) {
                val transactionId = runCatching {
                    UUID.fromString(response.readEntity(Map::class.java)["id"]?.toString())
                }.getOrNull()
                SettlementOutcome(settled = true, transactionId = transactionId)
            } else {
                log.warnf(
                    "transaction-service returned HTTP %d for SWIFT message %s settlement; " +
                        "message stays SENT",
                    response.status,
                    message.id,
                )
                SettlementOutcome(settled = false, transactionId = null)
            }
        } catch (ex: Exception) {
            throw SettlementUnavailableException(
                "transaction-service unreachable for SWIFT message ${message.id}",
                ex,
            )
        }
    }

    private companion object {
        const val MINOR_UNIT_SCALE = 2
        val ACCEPTED_STATUSES = setOf(200, 201, 202)
    }
}
