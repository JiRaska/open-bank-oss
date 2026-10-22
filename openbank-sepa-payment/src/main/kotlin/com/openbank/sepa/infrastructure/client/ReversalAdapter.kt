// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.client

import com.openbank.clients.transaction.TransactionCallOutcome
import com.openbank.clients.transaction.TransactionClient
import com.openbank.clients.transaction.model.ReverseTransactionRequest
import com.openbank.sepa.application.port.out.ReversalOutcome
import com.openbank.sepa.application.port.out.ReversalPort
import com.openbank.sepa.application.port.out.ReversalUnavailableException
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.CancellationException
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Reverses a booked SEPA settlement in transaction-service when a pacs.004 return arrives, through the
 * shared [TransactionClient] facade (ADR-0319).
 *
 * A reversal is confirmed only by HTTP 201 (new) or 200 (idempotent replay) carrying a COMPLETED
 * transaction with an id; the facade throws on any other 2xx body. A 409 is a concurrent-state conflict
 * and proves nothing (#13225), so it is [ReversalUnavailableException] like every other non-booking:
 * the caller records `reversalPerformed = false` instead of claiming a reversal that was never proven.
 */
@ApplicationScoped
class ReversalAdapter(@RestClient private val client: TransactionServiceClient) : ReversalPort {

    @Inject
    lateinit var self: ReversalAdapter

    private val log = Logger.getLogger(ReversalAdapter::class.java)
    private val transactions = TransactionClient(client)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun reverseTransaction(
        transactionId: UUID,
        idempotencyKey: String,
        reason: String,
    ): ReversalOutcome {
        try {
            return self.reverseWithResilience(transactionId, idempotencyKey, reason)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: ReversalUnavailableException) {
            throw ex
        } catch (ex: Exception) {
            throw ReversalUnavailableException(
                "transaction-service unavailable for reversal of transaction $transactionId",
                ex,
            )
        }
    }

    @Suppress("MagicNumber", "ThrowsCount")
    @Retry(
        maxRetries = 2,
        delay = 300,
        jitter = 150,
        retryOn = [Exception::class],
        abortOn = [ReversalUnavailableException::class, CancellationException::class],
    )
    @Timeout(8_000)
    open suspend fun reverseWithResilience(
        transactionId: UUID,
        idempotencyKey: String,
        reason: String,
    ): ReversalOutcome {
        val request = ReverseTransactionRequest(idempotencyKey = idempotencyKey, reason = reason)
        val outcome = transactions.reverse(transactionId, request).awaitSuspending()
        val reversed = when (outcome) {
            is TransactionCallOutcome.HttpSuccess -> outcome
            TransactionCallOutcome.Conflict -> throw ReversalUnavailableException(
                "transaction-service answered HTTP 409 (a concurrent-state conflict, not a reversal) " +
                    "for transaction $transactionId",
            )
            is TransactionCallOutcome.Rejected -> throw ReversalUnavailableException(
                "transaction-service refused reversal of transaction $transactionId with HTTP ${outcome.status}",
            )
        }
        if (reversed.status !in ACCEPTED_STATUSES) {
            throw ReversalUnavailableException(
                "Unexpected HTTP ${reversed.status} from transaction-service for reversal of $transactionId",
            )
        }
        val reversalId = reversed.body?.id
            ?: throw ReversalUnavailableException("Reversal response has no transaction id")
        log.infof("Reversal booked for transaction %s → reversal transaction %s", transactionId, reversalId)
        return ReversalOutcome(reversed = true, reversalTransactionId = reversalId)
    }

    private companion object {
        /** 201 for a new reversal, 200 for the provider's idempotent replay of the same key. */
        val ACCEPTED_STATUSES = setOf(200, 201)
    }
}
