// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.rest

import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import com.openbank.libs.idempotency.IdempotencyRecord
import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.libs.idempotency.ReserveResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/** The reserve → execute → save contract of [Psd2Idempotency.execute] (#10916). */
class Psd2IdempotencyTest {

    private val store = mockk<IdempotencyStore>(relaxUnitFun = true)
    private var executions = 0

    private suspend fun run(result: ReserveResult, fail: Boolean = false): Response {
        coEvery { store.reserve("k", "h", any()) } returns result
        return Psd2Idempotency.execute(
            store,
            "k",
            "h",
            replay = { Response.status(it.statusCode) },
            conflict = Psd2Idempotency::conflictResponse,
        ) {
            executions++
            if (fail) error("boom")
            Psd2Idempotency.Completed(Response.status(201).entity("new").build(), 201, "\"new\"")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun code(r: Response) =
        ((r.entity as Map<String, Any?>)["tppMessages"] as List<Map<String, Any?>>)[0]["code"]

    @Test
    fun `Reserved executes once and saves the fingerprinted response without releasing`(): Unit = runBlocking {
        val r = run(ReserveResult.Reserved)
        assertThat(r.status).isEqualTo(201)
        assertThat(executions).isEqualTo(1)
        coVerify(exactly = 1) { store.save("k", "h", 201, "\"new\"", any()) }
        coVerify(exactly = 0) { store.release(any(), any()) }
    }

    @Test
    fun `Replay returns the stored response and executes nothing`(): Unit = runBlocking {
        val r = run(ReserveResult.Replay(IdempotencyRecord("k", 201, "\"old\"", OffsetDateTime.now(), "h")))
        assertThat(r.status).isEqualTo(201)
        assertThat(r.entity).isEqualTo("\"old\"")
        assertThat(r.getHeaderString("X-Idempotency-Replayed")).isEqualTo("true")
        assertThat(executions).isZero()
        coVerify(exactly = 0) { store.release(any(), any()) }
    }

    @Test
    fun `Mismatch is 409 IDEMPOTENCY_KEY_REUSED in the tppMessages envelope`(): Unit = runBlocking {
        val r = run(ReserveResult.Mismatch)
        assertThat(r.status).isEqualTo(409)
        assertThat(code(r)).isEqualTo("IDEMPOTENCY_KEY_REUSED")
        assertThat(executions).isZero()
    }

    @Test
    fun `InFlight is 409 IDEMPOTENCY_REQUEST_IN_PROGRESS`(): Unit = runBlocking {
        val r = run(ReserveResult.InFlight)
        assertThat(r.status).isEqualTo(409)
        assertThat(code(r)).isEqualTo("IDEMPOTENCY_REQUEST_IN_PROGRESS")
        assertThat(executions).isZero()
    }

    @Test
    fun `a failing use case releases the reservation and rethrows the original error`(): Unit = runBlocking {
        coEvery { store.release("k", "h") } throws IllegalStateException("redis down")
        assertThatThrownBy { runBlocking { run(ReserveResult.Reserved, fail = true) } }.hasMessage("boom")
        coVerify(exactly = 1) { store.release("k", "h") }
        coVerify(exactly = 0) { store.save(any(), any<String>(), any(), any(), any()) }
    }

    @Test
    fun `a completed request whose marker was claimed meanwhile still answers its real outcome`(): Unit = runBlocking {
        coEvery { store.save("k", "h", 201, any(), any()) } throws IdempotencyKeyReusedException()
        val r = run(ReserveResult.Reserved)
        assertThat(r.status).isEqualTo(201)
        coVerify(exactly = 0) { store.release(any(), any()) }
    }
}
