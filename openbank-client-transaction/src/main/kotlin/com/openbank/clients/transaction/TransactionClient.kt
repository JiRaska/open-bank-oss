// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clients.transaction

import com.openbank.clients.transaction.api.TransactionsApi
import com.openbank.clients.transaction.model.InitiateTransactionRequest
import com.openbank.clients.transaction.model.ReverseTransactionRequest
import com.openbank.clients.transaction.model.TransactionPage
import com.openbank.clients.transaction.model.TransactionResponse
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * The ONLY place that interprets an HTTP answer from transaction-service (ADR-0319).
 *
 * **Consumers must not call the generated [TransactionsApi] directly.** It returns the raw JAX-RS
 * [Response], and every consumer used to re-derive "did it book?" from it, each a little differently.
 * Wrap the consumer's `@RestClient` sub-interface: `TransactionClient(client).initiate(request)`.
 *
 * The rule, once:
 *
 *  - a 2xx status proves HTTP success, before the body is read -> [TransactionCallOutcome.HttpSuccess];
 *  - [initiate] and [reverse] additionally require a readable transaction whose status is COMPLETED.
 *    The provider can return FAILED or an in-progress idempotent replay with the same 2xx status.
 *    Missing, unreadable or any other state is uncertain, never proof of a booked transaction;
 *  - [list] is a read: its HTTP success says nothing about the business state of its entries;
 *  - 409 -> [TransactionCallOutcome.Conflict]; 400 and 422 -> [TransactionCallOutcome.Rejected];
 *  - every other status (3xx, 401, 403, 404, 5xx) is thrown as ONE type, [TransactionHttpException], whether
 *    the client handed back a [Response] or threw a [WebApplicationException] (that one is its `cause`); and
 *    every failure that carries no status (refused or reset connection, timeout) is thrown unchanged. The
 *    caller retries; that is safe because the request carries its own idempotency key.
 *
 * Every [Response] that reaches this class is closed on every path, including the ones that throw.
 *
 * **Versioning.** The artifact version is the provider's API-contract version (ADR-0319 D2, ADR-0048), not an
 * axis of its own: this module has no `version.txt`, and consumers are `project()` dependencies inside the
 * monorepo, so there is no resolution step a client version could steer. Changing the TYPES the generated API
 * returns (as this facade's introduction did, `Uni<T>` to `Uni<Response>`) breaks the source of every
 * consumer, which is why all consumers switch in the same wave of pull requests.
 */
class TransactionClient(private val api: TransactionsApi) {

    fun initiate(request: InitiateTransactionRequest): Uni<TransactionCallOutcome<TransactionResponse>> =
        classify(api.initiateTransaction(request), TransactionResponse::class.java)
            .onItem().transform { requireCompleted(it) }

    fun reverse(
        transactionId: UUID,
        request: ReverseTransactionRequest,
    ): Uni<TransactionCallOutcome<TransactionResponse>> =
        classify(api.reverseTransaction(transactionId, request), TransactionResponse::class.java)
            .onItem().transform { requireCompleted(it) }

    fun list(accountId: UUID, limit: Int, cursor: String?): Uni<TransactionCallOutcome<TransactionPage>> =
        classify(api.listTransactions(accountId, limit, cursor), TransactionPage::class.java)

    internal companion object {
        private fun requireCompleted(
            outcome: TransactionCallOutcome<TransactionResponse>,
        ): TransactionCallOutcome<TransactionResponse> {
            if (outcome is TransactionCallOutcome.HttpSuccess &&
                (outcome.bodyError != null || outcome.body?.status != "COMPLETED")
            ) {
                val completionFailure = if (outcome.bodyError == null && outcome.body?.status == "FAILED") {
                    TransactionCompletionFailure.FAILED
                } else {
                    TransactionCompletionFailure.UNPROVEN
                }
                throw TransactionCompletionUncertainException.classified(
                    outcome.status,
                    outcome.bodyError,
                    completionFailure,
                )
            }
            return outcome
        }

        /** A refusal body is read at most this many bytes, whatever the server sends. */
        private const val PROBLEM_MAX_BYTES = 4096
        private const val PROBLEM_MAX_CHARS = 300
        private const val HTTP_OK = 200
        private const val HTTP_MULTIPLE_CHOICES = 300
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_CONFLICT = 409
        private const val HTTP_UNPROCESSABLE = 422

        fun <T> classify(call: Uni<Response>, type: Class<T>): Uni<TransactionCallOutcome<T>> = call
            .onItem().transform { response -> fromResponse(response, type) }
            .onFailure(WebApplicationException::class.java).recoverWithUni { failure ->
                // Uni (not Item): rethrowing from a recover-with-item function would wrap the original
                // exception in a CompositeException and hide its status from the caller.
                fromError<T>(failure as WebApplicationException)
            }

        /**
         * A TEST HOOK: how many times closing a Response itself failed. The module is a library without a
         * metrics registry, so no meter is registered; what operators can observe is the WARNING log line
         * ("closing a transaction-service Response failed", with the cause). A close failure is never
         * allowed to change a decided result. A consumer that wants a metric gets a callback here later.
         */
        internal val closeFailures = AtomicLong()

        private val log = System.getLogger(TransactionClient::class.java.name)

        /**
         * Closes [response] without letting a failing `close()` replace an outcome that is already decided:
         * a booked debit must not be reported as an error, and so retried, because releasing the connection
         * went wrong afterwards.
         */
        @Suppress("TooGenericExceptionCaught")
        private fun closeQuietly(response: Response) {
            try {
                response.close()
            } catch (e: Exception) {
                closeFailures.incrementAndGet()
                log.log(System.Logger.Level.WARNING, "closing a transaction-service Response failed", e)
            }
        }

        private fun <T> fromResponse(response: Response, type: Class<T>): TransactionCallOutcome<T> = try {
            when (val status = response.status) {
                in HTTP_OK until HTTP_MULTIPLE_CHOICES -> httpSuccess(response, status, type)
                HTTP_CONFLICT -> TransactionCallOutcome.Conflict
                HTTP_BAD_REQUEST, HTTP_UNPROCESSABLE -> rejected(response, status)
                else -> throw TransactionHttpException(status)
            }
        } finally {
            closeQuietly(response)
        }

        private fun <T> fromError(failure: WebApplicationException): Uni<TransactionCallOutcome<T>> {
            val response = failure.response
            return try {
                when (val status = response.status) {
                    in HTTP_OK until HTTP_MULTIPLE_CHOICES ->
                        Uni.createFrom().item(TransactionCallOutcome.HttpSuccess(status, null, failure))
                    HTTP_CONFLICT -> Uni.createFrom().item(TransactionCallOutcome.Conflict)
                    HTTP_BAD_REQUEST, HTTP_UNPROCESSABLE -> Uni.createFrom().item(rejected(response, status))
                    else -> Uni.createFrom().failure(TransactionHttpException(status, failure))
                }
            } finally {
                closeQuietly(response)
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun <T> httpSuccess(response: Response, status: Int, type: Class<T>): TransactionCallOutcome<T> = try {
            val body = if (response.hasEntity()) response.readEntity(type) else null
            TransactionCallOutcome.HttpSuccess(status, body, null)
        } catch (e: Exception) {
            TransactionCallOutcome.HttpSuccess(status, null, e)
        }

        private fun boundedText(response: Response): String =
            response.readEntity(InputStream::class.java).use { it.readNBytes(PROBLEM_MAX_BYTES) }
                .toString(Charsets.UTF_8)
                .take(PROBLEM_MAX_CHARS)

        @Suppress("TooGenericExceptionCaught")
        private fun rejected(response: Response, status: Int): TransactionCallOutcome.Rejected = try {
            val problem = if (response.hasEntity()) boundedText(response) else null
            TransactionCallOutcome.Rejected(status, problem)
        } catch (e: Exception) {
            TransactionCallOutcome.Rejected(status, null, e)
        }
    }
}

/**
 * transaction-service answered with a status that proves nothing about the booking (3xx, 401, 403, 404, 5xx).
 * Thrown so the caller retries; it is NOT a verdict that the call failed to book. [cause] is the client's own
 * `WebApplicationException` when there was one.
 */
class TransactionHttpException(val status: Int, cause: Throwable? = null) :
    RuntimeException("transaction-service answered HTTP $status", cause)

/**
 * The write answered successfully, but its transaction completion is unproven. Reconcile or retry
 * with the SAME idempotency key; never confirm the operation or submit it under a new key.
 * A returned FAILED state is not interpreted as proof that no ledger effect ever happened.
 */
class TransactionCompletionUncertainException(val status: Int, cause: Throwable? = null) :
    RuntimeException("transaction-service completion is unproven (HTTP $status)", cause) {
    // Keep the original constructor and its default-argument JVM signature unchanged.
    var completionFailure: TransactionCompletionFailure = TransactionCompletionFailure.UNPROVEN
        private set

    companion object {
        internal fun classified(
            status: Int,
            cause: Throwable?,
            completionFailure: TransactionCompletionFailure,
        ): TransactionCompletionUncertainException = TransactionCompletionUncertainException(status, cause).also {
            it.completionFailure = completionFailure
        }
    }
}

/** Bounded response metadata only; no body, provider text or inference that a request was replayed. */
enum class TransactionCompletionFailure {
    FAILED,
    UNPROVEN,
}
