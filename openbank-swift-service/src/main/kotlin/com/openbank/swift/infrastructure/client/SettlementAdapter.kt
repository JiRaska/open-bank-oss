// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.client

import com.openbank.clients.transaction.TransactionCallOutcome
import com.openbank.clients.transaction.TransactionClient
import com.openbank.clients.transaction.model.InitiateTransactionRequest
import com.openbank.clients.transaction.model.TransactionResponse
import com.openbank.swift.application.port.out.SettlementOutcome
import com.openbank.swift.application.port.out.SettlementPort
import com.openbank.swift.application.port.out.SettlementUnavailableException
import com.openbank.swift.domain.model.SwiftMessage
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CancellationException
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Books settled MT103 funds via transaction-service (ADR-0108).
 * Called after the clearing-simulator returns ACSC and swift-service transitions to SENT.
 * Uses the stable `swift-settlement-<messageId>` key for any explicit reconciliation.
 * The caller retains SENT on uncertainty; no automatic SENT redriver is introduced here.
 */
@ApplicationScoped
class SettlementAdapter(@RestClient private val client: TransactionServiceClient) : SettlementPort {

    private val log = Logger.getLogger(SettlementAdapter::class.java)
    private val transactions = TransactionClient(client)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun settle(message: SwiftMessage): SettlementOutcome {
        val sourceAccountId = message.orderingCustomerAccountId
            ?: return SettlementOutcome(settled = false, transactionId = null).also {
                log.warnf(
                    "No orderingCustomerAccountId for SWIFT message %s — skipping settlement booking",
                    message.id,
                )
            }

        val request = try {
            requestFor(message, sourceAccountId)
        } catch (e: DateTimeParseException) {
            // SwiftMessage.validate() guarantees YYYYMMDD at creation; a stored value that no longer parses is
            // bad data, not an outage: nothing is sent and the message stays SENT.
            log.errorf(
                e,
                "SWIFT message %s has an unparseable value date '%s'; not booking",
                message.id,
                message.valueDate,
            )
            return SettlementOutcome(settled = false, transactionId = null)
        }

        return try {
            when (val outcome = transactions.initiate(request).awaitSuspending()) {
                is TransactionCallOutcome.HttpSuccess -> booked(message, request, outcome)
                // transaction-service WAS reached and refused: the message stays SENT, as it always did.
                TransactionCallOutcome.Conflict -> refused(message, HTTP_CONFLICT)
                is TransactionCallOutcome.Rejected -> refused(message, outcome.status)
            }
        } catch (e: CancellationException) {
            // Cancellation is not an outage: rethrow so the coroutine unwinds instead of being retried as one.
            throw e
        } catch (ex: Exception) {
            // No proven completion (5xx, dropped connection): the caller retains SENT, never decided here.
            throw SettlementUnavailableException(
                "transaction-service unreachable for SWIFT message ${message.id}",
                ex,
            )
        }
    }

    // SwiftMessage.valueDate is MT103 basic ISO (YYYYMMDD) while transaction-service parses ISO-8601
    // (LocalDate.parse(request.valueDate)). Until the generated client typed the field the raw value went
    // on the wire unchanged and could not be parsed there; THIS conversion is what lets the booking through.
    private fun requestFor(message: SwiftMessage, sourceAccountId: UUID) = InitiateTransactionRequest(
        idempotencyKey = "swift-settlement-${message.id}",
        type = "DEBIT",
        sourceAccountId = sourceAccountId,
        amount = message.amount.amount,
        currencyCode = message.currency,
        description = "MT103 ${message.remittanceInfo ?: message.transactionReference}",
        valueDate = LocalDate.parse(message.valueDate, DateTimeFormatter.BASIC_ISO_DATE),
        rail = "SWIFT",
        instructionType = "ONE_OFF",
    )

    /**
     * HTTP 201 (the provider's answer to a create, including its idempotent replay) with a COMPLETED
     * transaction that matches the request is the ONLY settlement (#13217): type, source account, numeric
     * amount, currency, rail and instruction type, as sepa-instant's `SettlementAdapter` binds them.
     * valueDate is not compared - the provider resolves it to a business day. 200/202, a missing id or a
     * booking for something else leaves the message SENT; the shared facade has already thrown on a
     * missing, unreadable or non-final body.
     */
    private fun booked(
        message: SwiftMessage,
        request: InitiateTransactionRequest,
        outcome: TransactionCallOutcome.HttpSuccess<TransactionResponse>,
    ): SettlementOutcome {
        if (outcome.status != HTTP_CREATED) return refused(message, outcome.status)
        val tx = outcome.body ?: return refused(message, outcome.status)
        val id = tx.id ?: return unbound(message, "no transaction id")
        val amount = tx.amount ?: return unbound(message, "no numeric amount")
        val matchesRequest = tx.status == COMPLETED &&
            tx.type == request.type &&
            tx.sourceAccountId == request.sourceAccountId &&
            amount.compareTo(request.amount) == 0 &&
            tx.currencyCode == request.currencyCode &&
            tx.rail == request.rail &&
            tx.instructionType == request.instructionType
        if (!matchesRequest) return unbound(message, "a booking that does not match the request")
        return SettlementOutcome(settled = true, transactionId = id)
    }

    private fun unbound(message: SwiftMessage, reason: String): SettlementOutcome {
        log.warnf(
            "transaction-service answered with %s for SWIFT message %s settlement; message stays SENT",
            reason,
            message.id,
        )
        return SettlementOutcome(settled = false, transactionId = null)
    }

    private fun refused(message: SwiftMessage, status: Int): SettlementOutcome {
        log.warnf(
            "transaction-service returned HTTP %d for SWIFT message %s settlement; message stays SENT",
            status,
            message.id,
        )
        return SettlementOutcome(settled = false, transactionId = null)
    }

    private companion object {
        const val HTTP_CREATED = 201
        const val HTTP_CONFLICT = 409
        const val COMPLETED = "COMPLETED"
    }
}
