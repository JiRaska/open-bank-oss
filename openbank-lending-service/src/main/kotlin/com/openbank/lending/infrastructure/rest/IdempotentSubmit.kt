// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.rest

import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import com.openbank.libs.idempotency.IdempotencyRequestInProgressException
import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.libs.idempotency.ReserveResult
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jboss.logging.Logger

private const val HTTP_CREATED = 201
private val LOG: Logger = Logger.getLogger("com.openbank.lending.infrastructure.rest.IdempotentSubmit")

/**
 * The one idempotent-submit flow both lending application endpoints share (#10916, libs #10922).
 *
 * The key is claimed with [IdempotencyStore.reserve] BEFORE [submit] runs, so no application is
 * created unless the reservation succeeded:
 *  - [ReserveResult.Replay] → the stored response, with `X-Idempotency-Replayed: true`;
 *  - [ReserveResult.Mismatch] → [IdempotencyKeyReusedException] (409 IDEMPOTENCY_KEY_REUSED);
 *  - [ReserveResult.InFlight] → [IdempotencyRequestInProgressException] (409
 *    IDEMPOTENCY_REQUEST_IN_PROGRESS);
 *  - [ReserveResult.Reserved] → [submit] runs; a 201 is stored under [requestHash], anything else
 *    (a refusal response or an exception, including cancellation) releases the claim so a retry
 *    can run.
 *
 * Once [submit] has returned 201 the application EXISTS, so the claim is never released again:
 * if the [save] that completes it fails (store error, or the marker expired and the key was taken
 * meanwhile), the created response is still returned and the in-flight marker is left to expire,
 * so a retry answers 409 IN_PROGRESS rather than creating a second application. Releasing there
 * would hand the key to a retry with no durable dedupe behind it.
 *
 * [submit] must return a JSON [String] entity. A `null` [storeKey] (no key supplied) runs [submit]
 * unguarded — the pre-existing contract for un-keyed callers.
 */
internal suspend fun IdempotencyStore.submitOnce(
    storeKey: String?,
    requestHash: String,
    ttlSeconds: Long,
    submit: suspend () -> Response,
): Response {
    if (storeKey == null) return submit()
    when (val reservation = reserve(storeKey, requestHash)) {
        is ReserveResult.Replay -> return Response.status(reservation.record.statusCode)
            .entity(reservation.record.responseBody)
            .type(MediaType.APPLICATION_JSON)
            .header("X-Idempotency-Replayed", "true")
            .build()
        ReserveResult.Mismatch -> throw IdempotencyKeyReusedException()
        ReserveResult.InFlight -> throw IdempotencyRequestInProgressException()
        ReserveResult.Reserved -> Unit
    }
    var completed = false
    try {
        val response = submit()
        if (response.status != HTTP_CREATED) return response
        // The resource now exists: from here on the claim must never be released.
        completed = true
        saveCompleted(storeKey, requestHash, response, ttlSeconds)
        return response
    } finally {
        if (!completed) {
            withContext(NonCancellable) {
                runCatching { release(storeKey, requestHash) }
                    .onFailure { LOG.warnf(it, "could not release idempotency marker after a failed submit") }
            }
        }
    }
}

/**
 * Stores a 201 whose resource already exists. A failure is logged and swallowed, never rethrown:
 * the caller must answer the created response and keep the in-flight marker (see [submitOnce]).
 */
private suspend fun IdempotencyStore.saveCompleted(
    storeKey: String,
    requestHash: String,
    response: Response,
    ttlSeconds: Long,
) {
    try {
        save(storeKey, requestHash, response.status, response.entity as String, ttlSeconds)
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        LOG.warnf(
            e,
            "idempotent submit created the resource but could not store the response; " +
                "the in-flight marker is kept so a retry answers IN_PROGRESS, not a duplicate",
        )
    }
}
