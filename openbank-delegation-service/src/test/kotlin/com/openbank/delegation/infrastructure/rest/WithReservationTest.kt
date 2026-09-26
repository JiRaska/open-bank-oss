// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.delegation.infrastructure.rest

import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import com.openbank.libs.idempotency.IdempotencyRecord
import com.openbank.libs.idempotency.IdempotencyRequestInProgressException
import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.libs.idempotency.ReserveResult
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * Plain-unit coverage of the `IdempotencyStore.withReservation` extension shared by
 * `DelegationResource` and `DelegationPortfolioResource` (PR #10922 migration) — no Quarkus/Redis
 * needed, since the extension only calls the [IdempotencyStore] interface. Complements the
 * REST-level `DelegationIdempotencyFingerprintIT`, which cannot easily force a use-case failure
 * AFTER a real `reserve()` without stubbing a downstream network call.
 */
class WithReservationTest {

    private class FakeIdempotencyStore : IdempotencyStore {
        val released = mutableListOf<String>()
        val saved = mutableListOf<String>()
        var reserveResult: ReserveResult = ReserveResult.Reserved

        override suspend fun get(key: String): IdempotencyRecord? = null
        override suspend fun save(key: String, statusCode: Int, responseBody: String, ttlSeconds: Long) = Unit
        override suspend fun save(
            key: String,
            requestHash: String,
            statusCode: Int,
            responseBody: String,
            ttlSeconds: Long,
        ) {
            saved += key
        }
        override suspend fun reserve(key: String, requestHash: String, inFlightTtlSeconds: Long) = reserveResult
        override suspend fun release(key: String, requestHash: String) {
            released += key
        }
    }

    @Test
    fun `Reserved runs the block once and saves the result`() = runBlocking {
        val store = FakeIdempotencyStore()
        var invocations = 0
        val response = store.withReservation("k", "h") {
            invocations++
            Triple(201, "body", null)
        }
        assertThat(invocations).isEqualTo(1)
        assertThat(response.status).isEqualTo(201)
        assertThat(store.saved).containsExactly("k")
        assertThat(store.released).isEmpty()
    }

    @Test
    fun `a block failure releases the reservation and rethrows, without saving`() {
        val store = FakeIdempotencyStore()
        assertThatThrownBy {
            runBlocking {
                store.withReservation("k", "h") {
                    error("use case failed")
                }
            }
        }.hasMessage("use case failed")
        assertThat(store.released).containsExactly("k")
        assertThat(store.saved).isEmpty()
    }

    @Test
    fun `Replay returns the stored response and never invokes the block or saves again`() = runBlocking {
        val store = FakeIdempotencyStore()
        store.reserveResult = ReserveResult.Replay(
            IdempotencyRecord("k", 200, "cached", OffsetDateTime.now(), "h"),
        )
        var invocations = 0
        val response = store.withReservation("k", "h") {
            invocations++
            Triple(999, "should not run", null)
        }
        assertThat(invocations).isZero()
        assertThat(response.status).isEqualTo(200)
        assertThat(response.getHeaderString("X-Idempotency-Replayed")).isEqualTo("true")
        assertThat(store.saved).isEmpty()
    }

    @Test
    fun `Mismatch throws IdempotencyKeyReusedException without running the block`() {
        val store = FakeIdempotencyStore()
        store.reserveResult = ReserveResult.Mismatch
        var invocations = 0
        assertThatThrownBy {
            runBlocking {
                store.withReservation("k", "h") {
                    invocations++
                    Triple(200, "unreachable", null)
                }
            }
        }.isInstanceOf(IdempotencyKeyReusedException::class.java)
        assertThat(invocations).isZero()
    }

    @Test
    fun `InFlight throws IdempotencyRequestInProgressException without running the block`() {
        val store = FakeIdempotencyStore()
        store.reserveResult = ReserveResult.InFlight
        var invocations = 0
        assertThatThrownBy {
            runBlocking {
                store.withReservation("k", "h") {
                    invocations++
                    Triple(200, "unreachable", null)
                }
            }
        }.isInstanceOf(IdempotencyRequestInProgressException::class.java)
        assertThat(invocations).isZero()
    }
}
