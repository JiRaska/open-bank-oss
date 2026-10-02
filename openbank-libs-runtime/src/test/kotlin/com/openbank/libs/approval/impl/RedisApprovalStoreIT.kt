// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.approval.impl

import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.InvalidApprovalStateException
import com.openbank.libs.approval.SelfApprovalNotAllowedException
import io.mockk.every
import io.mockk.mockk
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.value.ReactiveValueCommands
import io.quarkus.redis.runtime.datasource.ReactiveRedisDataSourceImpl
import io.smallrye.mutiny.Uni
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the REAL [RedisApprovalStore] — and its compare-and-set Lua script — against a real Valkey.
 *
 * The race tests do not rely on scheduling luck: [readBarrier] holds every racer's GET until all
 * [RACE] of them have read the same snapshot, which is exactly the interleaving a check-then-SET
 * store cannot survive. Measured negative case (2026-10-01): against the pre-CAS store (GET, check
 * status in Kotlin, unconditional `SET ... EX`) three tests here go red — 8 of 8 racing consumers
 * spent one approval, 2 of 8 racing checkers were each told their (contradictory) decision was
 * recorded, and a write after expiry resurrected the evicted approval.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisApprovalStoreIT {

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
                .setMaxPoolSize(RACE * 2)
                .setMaxPoolWaiting(RACE * 4),
        )
    }
    private val ds by lazy { ReactiveRedisDataSourceImpl(vertx, client, RedisAPI.api(client)) }
    private val clock = Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC)
    private val store by lazy { RedisApprovalStore(ds, clock) }

    private companion object {
        const val RACE = 8
    }

    @BeforeEach
    fun flush(): Unit = runBlocking { ds.execute("FLUSHALL").awaitSuspending() }

    @AfterAll
    fun close() {
        if (valkey.isRunning) client.close()
        vertx.closeAndAwait()
        valkey.stop()
    }

    private suspend fun ttl(id: String) = ds.execute("TTL", "approval:$id").awaitSuspending()!!.toLong()

    /**
     * A data source whose `GET` only completes once [parties] callers have issued one — forcing
     * every racer to read the same pre-transition record before any of them writes.
     */
    private fun readBarrier(parties: Int): ReactiveRedisDataSource {
        val actual = ds.value(String::class.java)
        val arrived = AtomicInteger()
        val gate = CompletableFuture<Unit>()
        val values = mockk<ReactiveValueCommands<String, String>>()
        every { values.get(any()) } answers {
            val key = firstArg<String>()
            if (arrived.incrementAndGet() >= parties) gate.complete(Unit)
            actual.get(key).call { _ -> Uni.createFrom().completionStage(gate) }
        }
        every { values.set(any<String>(), any<String>(), any()) } answers {
            actual.set(firstArg<String>(), secondArg<String>(), thirdArg())
        }
        val wrapped = mockk<ReactiveRedisDataSource>()
        every { wrapped.value(String::class.java) } returns values
        every { wrapped.key(String::class.java) } returns ds.key(String::class.java)
        every { wrapped.execute(any<String>(), *anyVararg()) } answers {
            @Suppress("UNCHECKED_CAST")
            ds.execute(firstArg<String>(), *(secondArg<Array<String>>()))
        }
        return wrapped
    }

    @Test
    fun `PENDING to APPROVED records the checker and the decided TTL`(): Unit = runBlocking {
        val created = store.create("ledger.post", "acc-1", "maker", ttlSeconds = 600)
        assertThat(ttl(created.id)).isBetween(1L, 600L)

        val decided = store.decide(created.id, "checker", approve = true)!!

        assertThat(decided.status).isEqualTo(ApprovalStatus.APPROVED)
        assertThat(decided.decidedBy).isEqualTo("checker")
        assertThat(store.find(created.id)).isEqualTo(decided)
        assertThat(ttl(created.id)).isBetween(601L, 86_400L)
    }

    @Test
    fun `PENDING to REJECTED is terminal - it can be neither re-decided nor executed`(): Unit = runBlocking {
        val created = store.create("ledger.post", "acc-1", "maker")
        store.decide(created.id, "checker", approve = false)

        assertThatThrownBy { runBlocking { store.decide(created.id, "checker-2", approve = true) } }
            .isInstanceOf(InvalidApprovalStateException::class.java)
        assertThatThrownBy { runBlocking { store.markExecuted(created.id) } }
            .isInstanceOf(InvalidApprovalStateException::class.java)
        assertThat(store.find(created.id)?.status).isEqualTo(ApprovalStatus.REJECTED)
    }

    @Test
    fun `APPROVED to EXECUTED happens once and EXECUTED cannot be re-decided`(): Unit = runBlocking {
        val created = store.create("ledger.post", "acc-1", "maker")
        store.decide(created.id, "checker", approve = true)

        assertThat(store.markExecuted(created.id)?.status).isEqualTo(ApprovalStatus.EXECUTED)
        assertThatThrownBy { runBlocking { store.markExecuted(created.id) } }
            .isInstanceOf(InvalidApprovalStateException::class.java)
        assertThatThrownBy { runBlocking { store.decide(created.id, "checker-2", approve = true) } }
            .isInstanceOf(InvalidApprovalStateException::class.java)
    }

    @Test
    fun `PENDING cannot be executed and the maker cannot approve their own request`(): Unit = runBlocking {
        val created = store.create("ledger.post", "acc-1", "maker")

        assertThatThrownBy { runBlocking { store.markExecuted(created.id) } }
            .isInstanceOf(InvalidApprovalStateException::class.java)
        assertThatThrownBy { runBlocking { store.decide(created.id, "maker", approve = true) } }
            .isInstanceOf(SelfApprovalNotAllowedException::class.java)
        assertThat(store.find(created.id)?.status).isEqualTo(ApprovalStatus.PENDING)
    }

    @Test
    fun `an unknown or expired approval is null and is never recreated`(): Unit = runBlocking {
        assertThat(store.decide("missing", "checker", approve = true)).isNull()
        assertThat(store.markExecuted("missing")).isNull()
        assertThat(ds.execute("EXISTS", "approval:missing").awaitSuspending()!!.toInteger()).isZero()
    }

    @Test
    fun `racing checkers - exactly one decision is acknowledged and it is the one stored`(): Unit = runBlocking {
        val created = store.create("ledger.post", "acc-1", "maker")
        val racing = RedisApprovalStore(readBarrier(RACE), clock)

        val outcomes = (1..RACE).map { i ->
            async(Dispatchers.IO) { runCatching { racing.decide(created.id, "checker-$i", approve = i % 2 == 0) } }
        }.awaitAll()

        val acknowledged = outcomes.mapNotNull { it.getOrNull() }
        assertThat(acknowledged).hasSize(1)
        assertThat(outcomes.mapNotNull { it.exceptionOrNull() })
            .hasSize(RACE - 1)
            .allMatch { it is InvalidApprovalStateException }
        assertThat(store.find(created.id)).isEqualTo(acknowledged.single())
    }

    @Test
    fun `racing consumers - an approval is spent exactly once`(): Unit = runBlocking {
        val created = store.create("ledger.post", "acc-1", "maker")
        store.decide(created.id, "checker", approve = true)
        val racing = RedisApprovalStore(readBarrier(RACE), clock)

        val outcomes = (1..RACE).map {
            async(Dispatchers.IO) { runCatching { racing.markExecuted(created.id) } }
        }.awaitAll()

        assertThat(outcomes.mapNotNull { it.getOrNull() }).hasSize(1)
        assertThat(outcomes.mapNotNull { it.exceptionOrNull() })
            .hasSize(RACE - 1)
            .allMatch { it is InvalidApprovalStateException }
        assertThat(store.find(created.id)?.status).isEqualTo(ApprovalStatus.EXECUTED)
    }

    @Test
    fun `a write landing after expiry does not resurrect the approval`(): Unit = runBlocking {
        for (approved in listOf(false, true)) {
            val created = store.create("ledger.post", "acc-1", "maker")
            if (approved) store.decide(created.id, "checker", approve = true)
            // Evict between the store's GET and its write — the TTL-expiry window.
            val evicting = mockk<ReactiveRedisDataSource>()
            every { evicting.value(String::class.java) } returns ds.value(String::class.java)
            every { evicting.key(String::class.java) } returns ds.key(String::class.java)
            every { evicting.execute(any<String>(), *anyVararg()) } answers {
                val argv = secondArg<Array<String>>()
                ds.execute("DEL", argv[2]).chain { _ -> ds.execute(firstArg<String>(), *argv) }
            }
            val late = RedisApprovalStore(evicting, clock)

            val result = if (approved) late.markExecuted(created.id) else late.decide(created.id, "checker", true)

            assertThat(result).isNull()
            assertThat(store.find(created.id)).isNull()
        }
    }
}
