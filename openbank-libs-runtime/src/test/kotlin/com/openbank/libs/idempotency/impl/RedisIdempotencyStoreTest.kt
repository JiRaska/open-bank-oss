// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.idempotency.impl

import com.openbank.libs.api.error.ApiError
import com.openbank.libs.api.error.IdempotencyKeyReusedExceptionMapper
import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import com.openbank.libs.idempotency.RequestFingerprint
import io.mockk.every
import io.mockk.mockk
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.value.ReactiveValueCommands
import io.quarkus.redis.datasource.value.SetArgs
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Binds an `Idempotency-Key` to its request fingerprint. The Redis commands are backed by an
 * in-memory map so save -> encode -> decode -> compare genuinely round-trips.
 *
 * Negative case, measured: deleting the `stored != requestHash` throw in
 * `IdempotencyStore.lookup` turns `same key with a different payload is refused` red (the old
 * response is returned instead), while the replay and legacy cases stay green.
 */
class RedisIdempotencyStoreTest {

    private val clock = Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC)
    private val backing = mutableMapOf<String, String>()
    private val store = RedisIdempotencyStore(redis(), clock)

    private val first = RequestFingerprint.of("POST", "/api/v1/payments", """{"amount":"10.00"}""")
    private val other = RequestFingerprint.of("POST", "/api/v1/payments", """{"amount":"9999.00"}""")

    @Test
    fun `same key and same fingerprint replays the stored response`(): Unit = runBlocking {
        store.save("k1", first, 201, """{"id":"p|1"}""")

        val record = store.lookup("k1", first)

        assertThat(record).isNotNull
        assertThat(record!!.statusCode).isEqualTo(201)
        assertThat(record.responseBody).isEqualTo("""{"id":"p|1"}""")
        assertThat(record.requestHash).isEqualTo(first)
    }

    @Test
    fun `same key with a different payload is refused`(): Unit = runBlocking {
        store.save("k1", first, 201, """{"id":"p1"}""")

        assertThatThrownBy { runBlocking { store.lookup("k1", other) } }
            .isInstanceOf(IdempotencyKeyReusedException::class.java)
    }

    @Test
    fun `unknown key is a miss`(): Unit = runBlocking {
        assertThat(store.lookup("nope", first)).isNull()
    }

    @Test
    fun `legacy record without a fingerprint is treated as a match`(): Unit = runBlocking {
        store.save("legacy", 200, """{"a":"b|c"}""")
        backing["idempotency:old"] = "201|2026-09-01T00:00Z|{\"x\":1}"

        val viaNew = store.lookup("legacy", other)
        val viaRaw = store.lookup("old", other)

        assertThat(viaNew!!.requestHash).isNull()
        assertThat(viaNew.responseBody).isEqualTo("""{"a":"b|c"}""")
        assertThat(viaRaw!!.statusCode).isEqualTo(201)
        assertThat(viaRaw.responseBody).isEqualTo("{\"x\":1}")
    }

    @Test
    fun `reuse maps to 422 IDEMPOTENCY_KEY_REUSED`() {
        val response = IdempotencyKeyReusedExceptionMapper().toResponse(IdempotencyKeyReusedException("k1"))

        assertThat(response.status).isEqualTo(422)
        assertThat((response.entity as ApiError).code).isEqualTo("IDEMPOTENCY_KEY_REUSED")
    }

    private fun redis(): ReactiveRedisDataSource {
        val values = mockk<ReactiveValueCommands<String, String>>()
        every { values.get(any()) } answers { Uni.createFrom().item(backing[firstArg<String>()]) }
        every { values.set(any<String>(), any<String>(), any<SetArgs>()) } answers {
            backing[firstArg()] = secondArg()
            Uni.createFrom().voidItem()
        }
        val redis = mockk<ReactiveRedisDataSource>()
        every { redis.value(String::class.java) } returns values
        return redis
    }
}
