// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.idempotency.impl

import com.openbank.libs.api.error.ApiError
import com.openbank.libs.api.error.IdempotencyKeyReusedExceptionMapper
import com.openbank.libs.api.error.IdempotencyRequestInProgressExceptionMapper
import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import com.openbank.libs.idempotency.IdempotencyRecordCorruptException
import com.openbank.libs.idempotency.IdempotencyRequestInProgressException
import com.openbank.libs.idempotency.RequestFingerprint
import com.openbank.libs.idempotency.ReserveResult
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.value.ReactiveValueCommands
import io.quarkus.redis.datasource.value.SetArgs
import io.smallrye.mutiny.Uni
import io.vertx.mutiny.redis.client.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Binds an `Idempotency-Key` to its request fingerprint. The Redis commands are backed by an
 * in-memory map so save -> encode -> decode -> compare genuinely round-trips. The three Lua
 * scripts are emulated here by the fake below (single-threaded, so atomic by construction); the
 * Lua text itself runs against a real Valkey in [RedisIdempotencyStoreIT], which is the source of
 * truth: if this fake and that IT ever disagree, the IT is right and the fake must change.
 *
 * Negative case, measured: deleting the `stored != requestHash` throw in
 * `IdempotencyStore.lookup` turns `same key with a different payload is refused` red (the old
 * response is returned instead), while the replay and legacy cases stay green.
 */
class RedisIdempotencyStoreTest {

    private val clock = Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC)
    private val backing = mutableMapOf<String, String>()
    private val meters = SimpleMeterRegistry()
    private val store = RedisIdempotencyStore(redis(), clock, meters)

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
    fun `reserve save and release reject invalid request hashes without storing records`(): Unit = runBlocking {
        for (invalidHash in listOf("", "bad|hash")) {
            assertThatThrownBy { runBlocking { store.reserve("k1", invalidHash, 60) } }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { runBlocking { store.save("k1", invalidHash, 200, "{}", 60) } }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { runBlocking { store.release("k1", invalidHash) } }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThat(backing).isEmpty()
        }
    }

    @Test
    fun `malformed versioned record is not replayed`(): Unit = runBlocking {
        backing["idempotency:broken"] = "v2|hash-only"

        assertThat(store.get("broken")).isNull()
        assertThat(store.reserve("broken", first)).isEqualTo(ReserveResult.Mismatch)
        assertThat(backing["idempotency:broken"]).isEqualTo("v2|hash-only")
    }

    @Test
    fun `stored HTTP status range accepts endpoints and refuses adjacent values`(): Unit = runBlocking {
        for (status in listOf(100, 599)) {
            val key = "valid-$status"
            backing["idempotency:$key"] = "v2|$first|$status|2026-09-26T10:00Z|{}"

            assertThat(store.get(key)!!.statusCode).isEqualTo(status)
            assertThat(store.reserve(key, first)).isInstanceOf(ReserveResult.Replay::class.java)
        }

        for (status in listOf(99, 600)) {
            val key = "invalid-$status"
            val raw = "v2|$first|$status|2026-09-26T10:00Z|{}"
            backing["idempotency:$key"] = raw

            assertThatThrownBy { runBlocking { store.get(key) } }
                .isInstanceOf(IdempotencyRecordCorruptException::class.java)
            assertThatThrownBy { runBlocking { store.reserve(key, first) } }
                .isInstanceOf(IdempotencyRecordCorruptException::class.java)
            assertThat(backing["idempotency:$key"]).isEqualTo(raw)
        }
    }

    @Test
    fun `legacy record without a fingerprint is refused, never replayed`(): Unit = runBlocking {
        store.save("legacy", 200, """{"a":"b|c"}""")
        backing["idempotency:old"] = "201|2026-09-01T00:00Z|{\"x\":1}"

        assertThatThrownBy { runBlocking { store.lookup("legacy", other) } }
            .isInstanceOf(IdempotencyKeyReusedException::class.java)
        assertThatThrownBy { runBlocking { store.lookup("old", first) } }
            .isInstanceOf(IdempotencyKeyReusedException::class.java)
        assertThat(store.get("old")!!.statusCode).isEqualTo(201)
    }

    @Test
    fun `legacy reads are counted`(): Unit = runBlocking {
        backing["idempotency:old"] = "201|2026-09-01T00:00Z|{}"

        store.get("old")
        store.reserve("old", first)

        assertThat(meters.counter(RedisIdempotencyStore.LEGACY_METRIC).count()).isEqualTo(2.0)
    }

    @Test
    fun `reserve claims a free key, then reports the same request as in flight`(): Unit = runBlocking {
        assertThat(store.reserve("k1", first)).isEqualTo(ReserveResult.Reserved)
        assertThat(store.reserve("k1", first)).isEqualTo(ReserveResult.InFlight)
        assertThat(store.reserve("k1", other)).isEqualTo(ReserveResult.Mismatch)
        // A marker is not a response: a legacy get() must not replay it.
        assertThat(store.get("k1")).isNull()
    }

    @Test
    fun `reserve then save then reserve replays`(): Unit = runBlocking {
        store.reserve("k1", first)
        store.save("k1", first, 201, """{"id":"p1"}""")

        val again = store.reserve("k1", first)

        assertThat(again).isInstanceOf(ReserveResult.Replay::class.java)
        assertThat((again as ReserveResult.Replay).record.responseBody).isEqualTo("""{"id":"p1"}""")
        assertThat(store.reserve("k1", other)).isEqualTo(ReserveResult.Mismatch)
    }

    @Test
    fun `save never overwrites a record held by a different fingerprint`(): Unit = runBlocking {
        store.save("k1", first, 201, """{"id":"p1"}""")

        assertThatThrownBy { runBlocking { store.save("k1", other, 201, """{"id":"p2"}""") } }
            .isInstanceOf(IdempotencyKeyReusedException::class.java)
        assertThat(store.get("k1")!!.responseBody).isEqualTo("""{"id":"p1"}""")
    }

    @Test
    fun `save never overwrites another request's in-flight marker`(): Unit = runBlocking {
        store.reserve("k1", first)

        assertThatThrownBy { runBlocking { store.save("k1", other, 201, "{}") } }
            .isInstanceOf(IdempotencyKeyReusedException::class.java)
        assertThat(store.reserve("k1", first)).isEqualTo(ReserveResult.InFlight)
    }

    @Test
    fun `release frees only this request's marker`(): Unit = runBlocking {
        store.reserve("k1", first)
        store.release("k1", other)
        assertThat(store.reserve("k1", first)).isEqualTo(ReserveResult.InFlight)

        store.release("k1", first)
        assertThat(store.reserve("k1", first)).isEqualTo(ReserveResult.Reserved)

        store.save("k1", first, 200, "{}")
        store.release("k1", first)
        assertThat(store.reserve("k1", first)).isInstanceOf(ReserveResult.Replay::class.java)
    }

    @Test
    fun `reuse maps to 409 IDEMPOTENCY_KEY_REUSED without echoing the key`() {
        val exception = IdempotencyKeyReusedException()
        val response = IdempotencyKeyReusedExceptionMapper().toResponse(exception)

        assertThat(response.status).isEqualTo(409)
        assertThat((response.entity as ApiError).code).isEqualTo("IDEMPOTENCY_KEY_REUSED")
        assertThat(exception.message).doesNotContain("'")
    }

    @Test
    fun `in-flight maps to 409 IDEMPOTENCY_REQUEST_IN_PROGRESS`() {
        val response = IdempotencyRequestInProgressExceptionMapper().toResponse(IdempotencyRequestInProgressException())

        assertThat(response.status).isEqualTo(409)
        assertThat((response.entity as ApiError).code).isEqualTo("IDEMPOTENCY_REQUEST_IN_PROGRESS")
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
        every { redis.execute("EVAL", *anyVararg()) } answers {
            @Suppress("UNCHECKED_CAST")
            val a = (it.invocation.args[1] as Array<String>)
            Uni.createFrom().item(response(evalScript(a[0], a[2], a.drop(3))))
        }
        return redis
    }

    /** Kotlin twin of each Lua script in [RedisIdempotencyStore]; see the class KDoc. */
    private fun evalScript(script: String, key: String, argv: List<String>): String? {
        val cur = backing[key]
        return when (script) {
            RedisIdempotencyStore.RESERVE_SCRIPT -> cur ?: run {
                backing[key] = argv[0]
                null
            }
            RedisIdempotencyStore.SAVE_SCRIPT ->
                if (cur == null || cur.startsWith(argv[0]) || cur.startsWith(argv[1])) {
                    backing[key] = argv[2]
                    "1"
                } else {
                    "0"
                }
            RedisIdempotencyStore.SAVE_IF_ABSENT_SCRIPT ->
                if (cur == null) {
                    backing[key] = argv[0]
                    "1"
                } else {
                    "0"
                }
            RedisIdempotencyStore.RELEASE_SCRIPT ->
                if (cur != null && cur.startsWith(argv[0])) {
                    backing.remove(key)
                    "1"
                } else {
                    "0"
                }
            else -> error("unknown script")
        }
    }

    private fun response(value: String?): Response? = value?.let { v ->
        mockk<Response>().also { every { it.toString() } returns v }
    }
}
