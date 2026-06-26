// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sepa.infrastructure.client

import com.openbank.sepa.application.port.out.ReversalOutcome
import com.openbank.sepa.application.port.out.ReversalPort
import com.openbank.sepa.application.port.out.ReversalUnavailableException
import io.quarkus.oidc.client.OidcClient
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.util.UUID

@ApplicationScoped
class ReversalAdapter(
    @RestClient private val client: TransactionServiceClient,
    private val oidcClient: Instance<OidcClient>,
) : ReversalPort {

    @Inject
    lateinit var self: ReversalAdapter

    private val log = Logger.getLogger(ReversalAdapter::class.java)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun reverseTransaction(
        transactionId: UUID,
        idempotencyKey: String,
        reason: String,
    ): ReversalOutcome {
        try {
            return self.reverseWithResilience(transactionId, idempotencyKey, reason)
        } catch (ex: Exception) {
            throw ReversalUnavailableException(
                "transaction-service unavailable for reversal of transaction $transactionId",
                ex,
            )
        }
    }

    @Suppress("MagicNumber")
    @Retry(maxRetries = 2, delay = 300, jitter = 150, retryOn = [Exception::class])
    @Timeout(8_000)
    open suspend fun reverseWithResilience(
        transactionId: UUID,
        idempotencyKey: String,
        reason: String,
    ): ReversalOutcome {
        val token = oidcClient.get().tokens.awaitSuspending().accessToken
        val request = ReverseTransactionRequest(idempotencyKey = idempotencyKey, reason = reason)

        val response = client.reverseTransaction("Bearer $token", transactionId, request).awaitSuspending()
        return when (response.status) {
            Response.Status.CREATED.statusCode, Response.Status.OK.statusCode -> {
                val body = response.readEntity(Map::class.java)
                val reversalId = (body["id"] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                log.infof("Reversal booked for transaction %s → reversal transaction %s", transactionId, reversalId)
                ReversalOutcome(reversed = true, reversalTransactionId = reversalId)
            }
            Response.Status.CONFLICT.statusCode -> {
                log.infof("Reversal already exists for transaction %s (idempotent)", transactionId)
                ReversalOutcome(reversed = true, reversalTransactionId = null)
            }
            else -> {
                log.errorf(
                    "transaction-service returned %d for reversal of transaction %s",
                    response.status,
                    transactionId,
                )
                throw ReversalUnavailableException("Unexpected status ${response.status} from transaction-service")
            }
        }
    }
}
