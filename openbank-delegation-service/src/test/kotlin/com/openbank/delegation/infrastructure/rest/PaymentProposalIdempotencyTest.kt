// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.delegation.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.idempotency.IdempotencyRecord
import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.libs.idempotency.ReserveResult
import io.mockk.mockk
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * The edge rejects with a FIXED key per proposal (`mcp-proposal-reject-<id>`), so whatever the
 * store keeps for that key is what every retry gets. A transient 5xx must therefore never be kept:
 * if it were, the customer could not reject the proposal again until the record expired.
 */
class PaymentProposalIdempotencyTest {

    /** Minimal store with real replay semantics: a saved record is replayed, a released key is free. */
    private class ReplayingStore : IdempotencyStore {
        val records = mutableMapOf<String, IdempotencyRecord>()
        val inFlight = mutableSetOf<String>()

        override suspend fun get(key: String): IdempotencyRecord? = records[key]
        override suspend fun save(key: String, statusCode: Int, responseBody: String, ttlSeconds: Long) = Unit
        override suspend fun save(
            key: String,
            requestHash: String,
            statusCode: Int,
            responseBody: String,
            ttlSeconds: Long,
        ) {
            inFlight -= key
            records[key] = IdempotencyRecord(key, statusCode, responseBody, OffsetDateTime.now(), requestHash)
        }
        override suspend fun reserve(key: String, requestHash: String, inFlightTtlSeconds: Long): ReserveResult {
            records[key]?.let { return ReserveResult.Replay(it) }
            if (!inFlight.add(key)) return ReserveResult.InFlight
            return ReserveResult.Reserved
        }
        override suspend fun release(key: String, requestHash: String) {
            inFlight -= key
        }
    }

    private val store = ReplayingStore()
    private val resource = PaymentProposalResource(mockk(relaxed = true), store, ObjectMapper())
    private val body = mapOf("partyId" to "p")

    private fun call(status: Int): Response = runBlocking {
        resource.idempotent("reject", "id:p", "mcp-proposal-reject-id", body) {
            Response.status(status).entity(mapOf("status" to status)).build()
        }
    }

    @Test
    fun `a 5xx under the fixed reject key is not replayed, so the retry runs`() {
        assertThat(call(503).status).isEqualTo(503)
        assertThat(store.records).isEmpty()
        val retry = call(200)
        assertThat(retry.status).isEqualTo(200)
        assertThat(retry.getHeaderString("X-Idempotency-Replayed")).isNull()
    }

    @Test
    fun `a definitive answer is still stored and replayed`() {
        assertThat(call(200).status).isEqualTo(200)
        val replay = call(500)
        assertThat(replay.status).isEqualTo(200)
        assertThat(replay.getHeaderString("X-Idempotency-Replayed")).isEqualTo("true")
    }
}
