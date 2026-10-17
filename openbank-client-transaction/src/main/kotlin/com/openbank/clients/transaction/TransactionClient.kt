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
import java.util.UUID

/**
 * The ONLY place that interprets an HTTP answer from transaction-service (ADR-0319).
 *
 * The generated [TransactionsApi] returns the raw JAX-RS [Response]; it must not reach callers,
 * because every consumer used to re-derive "did it book?" from it and they derived it differently.
 * The rule, once:
 *
 *  - a 2xx status is PROVEN from the response line, before any body is touched -> [TransactionCallOutcome.Booked].
 *    The body is read lazily afterwards inside a try: an unreadable body is `bodyError`, never a failure,
 *    because the money has already moved;
 *  - 409 -> [TransactionCallOutcome.Conflict]; 400 and 422 -> [TransactionCallOutcome.Rejected];
 *  - every other status (3xx, 401, 403, 404, 5xx) and every failure that carries no status (refused or
 *    reset connection, timeout) is THROWN, unchanged, so the caller retries. Retrying is safe because
 *    the request carries its own idempotency key.
 *
 * Quarkus' REST client throws a [WebApplicationException] for a status >= 400 (its default response
 * mapper), a plain client hands the [Response] back; both shapes classify identically.
 */
class TransactionClient(private val api: TransactionsApi) {

    fun initiate(request: InitiateTransactionRequest): Uni<TransactionCallOutcome<TransactionResponse>> =
        classify(api.initiateTransaction(request), TransactionResponse::class.java)

    fun reverse(
        transactionId: UUID,
        request: ReverseTransactionRequest,
    ): Uni<TransactionCallOutcome<TransactionResponse>> =
        classify(api.reverseTransaction(transactionId, request), TransactionResponse::class.java)

    fun list(accountId: UUID, limit: Int, cursor: String?): Uni<TransactionCallOutcome<TransactionPage>> =
        classify(api.listTransactions(accountId, limit, cursor), TransactionPage::class.java)

    companion object {
        private const val PROBLEM_MAX_CHARS = 300
        private const val HTTP_OK = 200
        private const val HTTP_MULTIPLE_CHOICES = 300
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_CONFLICT = 409
        private const val HTTP_UNPROCESSABLE = 422

        /** Exposed for tests of the rule itself; production code calls the typed methods above. */
        fun <T> classify(call: Uni<Response>, type: Class<T>): Uni<TransactionCallOutcome<T>> = call
            .onItem().transform { response -> fromResponse(response, type) }
            .onFailure(WebApplicationException::class.java).recoverWithUni { failure ->
                // Uni (not Item): rethrowing from a recover-with-item function would wrap the original
                // exception in a CompositeException and hide its status from the caller.
                fromError<T>(failure as WebApplicationException)?.let { Uni.createFrom().item(it) }
                    ?: Uni.createFrom().failure(failure)
            }

        private fun <T> fromResponse(response: Response, type: Class<T>): TransactionCallOutcome<T> = response.use {
            when (val status = it.status) {
                in HTTP_OK until HTTP_MULTIPLE_CHOICES -> booked(it, status, type)
                HTTP_CONFLICT -> TransactionCallOutcome.Conflict
                HTTP_BAD_REQUEST, HTTP_UNPROCESSABLE -> TransactionCallOutcome.Rejected(status, problemOf(it))
                else -> throw TransactionHttpException(status)
            }
        }

        private fun <T> fromError(failure: WebApplicationException): TransactionCallOutcome<T>? {
            val response = failure.response
            return when (val status = response.status) {
                in HTTP_OK until HTTP_MULTIPLE_CHOICES -> TransactionCallOutcome.Booked(status, null, failure)
                HTTP_CONFLICT -> TransactionCallOutcome.Conflict
                HTTP_BAD_REQUEST, HTTP_UNPROCESSABLE -> TransactionCallOutcome.Rejected(status, problemOf(response))
                else -> null
            }
        }

        private fun <T> booked(response: Response, status: Int, type: Class<T>): TransactionCallOutcome<T> {
            if (!response.hasEntity()) return TransactionCallOutcome.Booked(status, null, null)
            val read = runCatching { response.readEntity(type) }
            return TransactionCallOutcome.Booked(status, read.getOrNull(), read.exceptionOrNull())
        }

        private fun problemOf(response: Response): String? = runCatching {
            if (response.hasEntity()) response.readEntity(String::class.java).take(PROBLEM_MAX_CHARS) else null
        }.getOrNull()
    }
}

/**
 * transaction-service answered with a status that proves nothing about the booking (3xx, 401, 403, 404,
 * 5xx). Thrown so the caller retries; it is NOT a verdict that the call failed to book.
 */
class TransactionHttpException(val status: Int) : RuntimeException("transaction-service answered HTTP $status")
