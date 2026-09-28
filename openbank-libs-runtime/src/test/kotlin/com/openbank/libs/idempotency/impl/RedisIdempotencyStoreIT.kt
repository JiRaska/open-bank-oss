// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.idempotency.impl

import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import com.openbank.libs.idempotency.ReserveResult
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.quarkus.redis.runtime.datasource.ReactiveRedisDataSourceImpl
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.redis.client.Redis
import io.vertx.mutiny.redis.client.RedisAPI
import io.vertx.redis.client.RedisOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Runs the REAL [RedisIdempotencyStore] — and therefore its three Lua scripts — against a real
 * Valkey, the image the fleet's service test resources use. [RedisIdempotencyStoreTest] drives a
 * Kotlin twin of the scripts; this IT is the source of truth for what the Lua actually does.
 *
 * Negative cases, measured: dropping `if cur then return cur end` from RESERVE_SCRIPT, the
 * hash-prefix compare from SAVE_SCRIPT, or the prefix check from RELEASE_SCRIPT each turns the
 * matching test here red.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisIdempotencyStoreIT {

    private val valkey: GenericContainer<*> = GenericContainer(DockerImageName.parse("valkey/valkey:7.2-alpine"))
        .withExposedPorts(6379)

    @BeforeAll
    fun start() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is required for this IT")
        valkey.start()
    }

    private val vertx = Vertx.vertx()
    private val client by lazy {
        Redis.createClient(
            vertx,
            RedisOptions()
                .setConnectionString("redis://${valkey.host}:${valkey.firstMappedPort}")
                // Room for the 50-way race below; the default wait queue (24) rejects it client-side.
                .setMaxPoolSize(RACE)
                .setMaxPoolWaiting(RACE * 2),
        )
    }
    private val ds by lazy { ReactiveRedisDataSourceImpl(vertx, client, RedisAPI.api(client)) }
    private val clock = Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC)
    private val meters = SimpleMeterRegistry()
    private val store by lazy { RedisIdempotencyStore(ds, clock, meters) }

    private companion object {
        const val RACE = 50
    }

    private val first = "a".repeat(64)
    private val other = "b".repeat(64)

    @BeforeEach
    fun flush(): Unit = runBlocking { cmd("FLUSHALL") }

    @AfterAll
    fun close() {
        if (valkey.isRunning) client.close()
        vertx.closeAndAwait()
        valkey.stop()
    }

    private suspend fun cmd(vararg args: String): String? = ds.execute(args[0], *args.drop(1).toTypedArray())
        .awaitSuspending()?.toString()

    private suspend fun raw(key: String) = cmd("GET", "idempotency:$key")

    private suspend fun pttl(key: String) = cmd("PTTL", "idempotency:$key")!!.toLong()

    @Test
    fun `reserve on an empty key sets an in-flight marker with the in-flight TTL`(): Unit = runBlocking {
        assertThat(store.reserve("k", first, 30)).isEqualTo(ReserveResult.Reserved)

        assertThat(raw("k")).isEqualTo("inflight|$first|2026-09-26T10:00Z")
        assertThat(pttl("k")).isBetween(1L, 30_000L)
        assertThat(store.get("k")).`as`("a marker is never a response").isNull()
    }

    @Test
    fun `reserve again is InFlight for the same hash and Mismatch for another`(): Unit = runBlocking {
        store.reserve("k", first, 30)
        val marker = raw("k")

        assertThat(store.reserve("k", first, 30)).isEqualTo(ReserveResult.InFlight)
        assertThat(store.reserve("k", other, 30)).isEqualTo(ReserveResult.Mismatch)
        assertThat(raw("k")).`as`("reserve never overwrites").isEqualTo(marker)
    }

    @Test
    fun `save over own marker stores the record with the record TTL, then reserve replays`(): Unit = runBlocking {
        store.reserve("k", first, 30)
        store.save("k", first, 201, """{"id":"p|1"}""", 3600)

        assertThat(raw("k")).isEqualTo("v2|$first|201|2026-09-26T10:00Z|{\"id\":\"p|1\"}")
        assertThat(pttl("k")).isBetween(30_001L, 3_600_000L)

        val replay = store.reserve("k", first, 30)
        assertThat(replay).isInstanceOf(ReserveResult.Replay::class.java)
        val record = (replay as ReserveResult.Replay).record
        assertThat(record.statusCode).isEqualTo(201)
        assertThat(record.responseBody).isEqualTo("""{"id":"p|1"}""")
        assertThat(record.requestHash).isEqualTo(first)
        assertThat(store.reserve("k", other, 30)).isEqualTo(ReserveResult.Mismatch)
    }

    @Test
    fun `save on an empty key and re-save by the same hash both write`(): Unit = runBlocking {
        store.save("k", first, 200, "one", 60)
        store.save("k", first, 200, "two", 60)

        assertThat(store.get("k")!!.responseBody).isEqualTo("two")
    }

    @Test
    fun `save by a different hash over a record throws and leaves the bytes unchanged`(): Unit = runBlocking {
        store.save("k", first, 201, "original", 3600)
        val before = raw("k")

        assertThatThrownBy { runBlocking { store.save("k", other, 201, "hijack", 3600) } }
            .isInstanceOf(IdempotencyKeyReusedException::class.java)
        assertThat(raw("k")).isEqualTo(before)
    }

    @Test
    fun `save by a different hash over another request's marker throws and leaves it`(): Unit = runBlocking {
        store.reserve("k", first, 30)
        val before = raw("k")

        assertThatThrownBy { runBlocking { store.save("k", other, 201, "hijack", 3600) } }
            .isInstanceOf(IdempotencyKeyReusedException::class.java)
        assertThat(raw("k")).isEqualTo(before)
    }

    @Test
    fun `release deletes only this hash's marker, never a record`(): Unit = runBlocking {
        store.reserve("k", first, 30)

        store.release("k", other)
        assertThat(raw("k")).startsWith("inflight|$first|")

        store.release("k", first)
        assertThat(raw("k")).isNull()

        store.save("k", first, 200, "{}", 60)
        store.release("k", first)
        assertThat(raw("k")).startsWith("v2|$first|")
    }

    @Test
    fun `a legacy record is replayed as a match and counted`(): Unit = runBlocking {
        cmd("SET", "idempotency:old", "201|2026-09-01T00:00Z|{\"x\":1}", "EX", "60")

        val result = store.reserve("old", other, 30)

        assertThat(result).isInstanceOf(ReserveResult.Replay::class.java)
        assertThat((result as ReserveResult.Replay).record.requestHash).isNull()
        assertThat(result.record.responseBody).isEqualTo("{\"x\":1}")
        assertThat(meters.counter(RedisIdempotencyStore.LEGACY_METRIC).count()).isGreaterThanOrEqualTo(1.0)
        assertThat(raw("old")).`as`("legacy record untouched").isEqualTo("201|2026-09-01T00:00Z|{\"x\":1}")
    }

    @Test
    fun `fifty concurrent reserves of one key yield exactly one Reserved`(): Unit = runBlocking {
        val results = (1..RACE).map { async(Dispatchers.IO) { store.reserve("race", first, 30) } }.awaitAll()

        assertThat(results.count { it == ReserveResult.Reserved }).isEqualTo(1)
        assertThat(results.count { it == ReserveResult.InFlight }).isEqualTo(RACE - 1)
    }
}
