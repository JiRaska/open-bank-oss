// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.rest

import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import com.openbank.libs.idempotency.IdempotencyRecord
import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.libs.idempotency.ReserveResult
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jboss.logging.Logger

/**
 * The one reserve → execute → fingerprinted-save flow every PSD2 create endpoint goes through
 * (#10916). The key contract each surface already had is unchanged — the caller builds the key:
 *
 *  - bespoke `/open-banking/v2/payments/…`: the `Idempotency-Key` header, namespaced by TPP + product;
 *  - bespoke `/open-banking/v2/consents` and every Berlin Group `/v1/…` create: `X-Request-ID`,
 *    which NextGenPSD2 defines as the request identifier and this service has always keyed on,
 *    namespaced by TPP (+ product for payments).
 *
 * What changes is that the key is now bound to a [com.openbank.libs.idempotency.RequestFingerprints]
 * hash of the request that actually executes, so the same key with a different payment (amount,
 * creditor, Consent-ID, …) is a 409 instead of a replay of the first response.
 *
 * Conflicts are rendered in this service's Berlin Group `tppMessages` envelope, NOT thrown: the
 * libs mappers for [IdempotencyKeyReusedException] answer the generic ApiError shape, and a second,
 * service-local mapper for the same type would collide non-deterministically (#526).
 */
internal object Psd2Idempotency {
    const val KEY_REUSED = "IDEMPOTENCY_KEY_REUSED"
    const val IN_PROGRESS = "IDEMPOTENCY_REQUEST_IN_PROGRESS"
    private const val KEY_REUSED_TEXT = "The request identifier was already used for a different request"
    private const val IN_PROGRESS_TEXT = "A request with this identifier is still being processed; retry later"

    private val log = Logger.getLogger(Psd2Idempotency::class.java)

    /** A completed create: the response to return now and the JSON body to store for replays. */
    class Completed(val response: Response, val statusCode: Int, val storedBody: String)

    /**
     * [work] runs ONLY after [IdempotencyStore.reserve] answered Reserved. A failure in [work]
     * releases the in-flight marker (under [NonCancellable], and a failing release never masks the
     * original exception) so a retry can run; nothing is released after success or on a replay.
     */
    @Suppress("TooGenericExceptionCaught") // release on ANY failure, incl. cancellation; always rethrown
    suspend fun execute(
        store: IdempotencyStore,
        key: String,
        requestHash: String,
        replay: (IdempotencyRecord) -> Response.ResponseBuilder,
        conflict: (code: String, text: String) -> Response.ResponseBuilder,
        work: suspend () -> Completed,
    ): Response = when (val reserved = store.reserve(key, requestHash)) {
        is ReserveResult.Replay -> replay(reserved.record)
            .entity(reserved.record.responseBody)
            .type(MediaType.APPLICATION_JSON)
            .header("X-Idempotency-Replayed", "true")
            .build()
        ReserveResult.Mismatch -> conflict(KEY_REUSED, KEY_REUSED_TEXT).build()
        ReserveResult.InFlight -> conflict(IN_PROGRESS, IN_PROGRESS_TEXT).build()
        ReserveResult.Reserved -> {
            val done = try {
                work()
            } catch (e: Throwable) {
                withContext(NonCancellable) { runCatching { store.release(key, requestHash) } }
                throw e
            }
            try {
                store.save(key, requestHash, done.statusCode, done.storedBody)
            } catch (e: IdempotencyKeyReusedException) {
                // The payment/consent already executed; answering 409 now would invite the TPP to
                // retry under a fresh key and execute it twice. Our marker was lost (in-flight TTL
                // expired and another request claimed the key) — return the real outcome.
                log.warnf(e, "Idempotency record for a completed request was claimed by a different request")
            }
            done.response
        }
    }

    /** 409 in the Berlin Group `tppMessages` envelope, as every other psd2 error. */
    fun conflictResponse(code: String, text: String): Response.ResponseBuilder =
        Response.status(Response.Status.CONFLICT).entity(BerlinXs2aMappers.tppError(code, text))
}
