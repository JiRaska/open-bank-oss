// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.rest

import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import com.openbank.libs.idempotency.IdempotencyRecord
import com.openbank.libs.idempotency.IdempotencyRequestInProgressException
import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.libs.idempotency.ReserveResult
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * #10958 review: once `submit` has returned 201 the application exists, and lending has no durable
 * dedupe behind the key — so a failing `save` must NOT release the marker, or the client's retry
 * creates a second application.
 */
class IdempotentSubmitTest {

    private val store = FlakyStore()
    private var applications = 0

    private suspend fun post(): Response = store.submitOnce("k", "h", TTL) {
        applications++
        Response.status(201).entity("""{"id":"$applications"}""").build()
    }

    @Test
    fun `a save failing after submit keeps the claim - retry is IN_PROGRESS, not a second application`() {
        store.saveFailure = IllegalStateException("redis down")
        val first = runBlocking { post() }
        assertThat(first.status).isEqualTo(201)

        assertThatThrownBy { runBlocking { post() } }
            .isInstanceOf(IdempotencyRequestInProgressException::class.java)
        assertThat(applications).isEqualTo(1)
        assertThat(store.released).isZero()
    }

    @Test
    fun `a save refused as key reuse after a successful submit still answers the created 201 and keeps the claim`() {
        store.saveFailure = IdempotencyKeyReusedException()
        assertThat(runBlocking { post() }.status).isEqualTo(201)
        assertThat(store.released).isZero()
        assertThatThrownBy { runBlocking { post() } }
            .isInstanceOf(IdempotencyRequestInProgressException::class.java)
        assertThat(applications).isEqualTo(1)
    }

    @Test
    fun `a failed submit releases the claim, and a failing release does not mask the original error`() {
        store.releaseFailure = IllegalStateException("redis down")
        assertThatThrownBy {
            runBlocking { store.submitOnce("k", "h", TTL) { throw IllegalArgumentException("refused") } }
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessage("refused")
        assertThat(store.released).isEqualTo(1)
    }

    @Test
    fun `cancellation during submit propagates and releases the claim`() {
        assertThatThrownBy {
            runBlocking { store.submitOnce("k", "h", TTL) { throw CancellationException("client gone") } }
        }.isInstanceOf(CancellationException::class.java)
        assertThat(store.released).isEqualTo(1)
    }

    private class FlakyStore : IdempotencyStore {
        var saveFailure: Exception? = null
        var releaseFailure: Exception? = null
        var released = 0
        val saved = mutableMapOf<String, IdempotencyRecord>()
        val markers = mutableMapOf<String, String>()

        override suspend fun get(key: String) = saved[key]
        override suspend fun save(key: String, statusCode: Int, responseBody: String, ttlSeconds: Long) =
            throw UnsupportedOperationException()
        override suspend fun save(
            key: String,
            requestHash: String,
            statusCode: Int,
            responseBody: String,
            ttlSeconds: Long,
        ) {
            saveFailure?.let { throw it }
            markers.remove(key)
            saved[key] = IdempotencyRecord(key, statusCode, responseBody, java.time.OffsetDateTime.now(), requestHash)
        }
        override suspend fun reserve(key: String, requestHash: String, inFlightTtlSeconds: Long): ReserveResult {
            saved[key]?.let { return ReserveResult.Replay(it) }
            markers[key]?.let { return ReserveResult.InFlight }
            markers[key] = requestHash
            return ReserveResult.Reserved
        }
        override suspend fun release(key: String, requestHash: String) {
            released++
            releaseFailure?.let { throw it }
            markers.remove(key)
        }
    }

    private companion object {
        const val TTL = 300L
    }
}
