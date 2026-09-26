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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private const val HTTP_CREATED = 201

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
    var stored = false
    try {
        val response = submit()
        if (response.status == HTTP_CREATED) {
            save(storeKey, requestHash, response.status, response.entity as String, ttlSeconds)
            stored = true
        }
        return response
    } finally {
        if (!stored) withContext(NonCancellable) { release(storeKey, requestHash) }
    }
}
