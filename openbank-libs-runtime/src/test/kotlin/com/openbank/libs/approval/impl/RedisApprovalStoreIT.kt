// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.approval.impl

import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.ApprovalStoreContractTest
import com.openbank.libs.approval.InvalidApprovalStateException
import com.openbank.libs.approval.MakerActorKind
import com.openbank.libs.approval.SelfApprovalNotAllowedException
import io.mockk.every
import io.mockk.mockk
import io.quarkus.redis.datasource.ReactiveRedisDataSource
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
 * The production [ApprovalStore] bound to the shared contract (#3349), against a REAL Valkey — the
 * image the fleet's service test resources use — so the Lua scripts that make every transition
 * atomic are what is under test, not a Kotlin imitation of them.
 *
 * `RedisApprovalStore.decide` refusing `decidedBy == makerId` is the fleet-wide enforcement point
 * for segregation of duties on a four-eyes action, and the contract's self-approval cases are what
 * make deleting it go red here.
 *
 * Without Docker this class is skipped locally, and FAILS when `CI=true`: a skipped run of the only
 * test of these invariants must not read as a pass where it matters.
 *
 * Negative cases, measured on the previous GET-then-SET implementation against this container:
 * 3 of 5 concurrent `markExecuted` calls succeeded, an approve racing a reject both succeeded, and a
 * second service's store listed and decided the first service's approval.
 *
 * The race tests below (from #11770) do not rely on scheduling luck: [writeBarrier] holds every
 * racer's first write until all [RACE] of them have finished reading, which is exactly the
 * interleaving a check-then-write store cannot survive. Measured against the pre-CAS store: 8 of 8
 * racing consumers spent one approval, 2 of 8 racing checkers were each told their (contradictory)
 * decision was recorded, and a write after expiry resurrected the evicted approval.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisApprovalStoreIT : ApprovalStoreContractTest() {

    private val valkey: GenericContainer<*> = GenericContainer(DockerImageName.parse("valkey/valkey:7.2-alpine"))
        .withExposedPorts(6379)

    @BeforeAll
    fun start() {
        val docker = DockerClientFactory.instance().isDockerAvailable
        check(docker || System.getenv("CI") != "true") { "Docker is required for RedisApprovalStoreIT in CI" }
        assumeTrue(docker, "Docker is required for this IT")
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

    override fun newStore(namespace: String, maxPendingPerMakerAction: Int): ApprovalStore =
        RedisApprovalStore(ds, clock, namespace, maxPendingPerMakerAction)

    @BeforeEach
    fun flush(): Unit = runBlocking { cmd("FLUSHALL") }

    @AfterAll
    fun close() {
        if (valkey.isRunning) client.close()
        vertx.closeAndAwait()
        valkey.stop()
    }

    private suspend fun cmd(vararg args: String): String? =
        ds.execute(args[0], *args.drop(1).toTypedArray()).awaitSuspending()?.toString()

    @Test
    fun `records live under the service namespace with the approval TTL, and findPending reads the index`(): Unit =
        runBlocking {
            val store = newStore(namespace = "openbank-interest-service")
            val pending = store.create("interest.create", null, "maker-1", ttlSeconds = 600)

            assertThat(cmd("TYPE", "approval-v2:openbank-interest-service:${pending.id}")).isEqualTo("hash")
            assertThat(cmd("TTL", "approval-v2:openbank-interest-service:${pending.id}")!!.toLong()).isBetween(1L, 600L)
            assertThat(cmd("ZCARD", "approval-v2:openbank-interest-service:pending")).isEqualTo("1")
            assertThat(cmd("EXISTS", "approval:${pending.id}")).isEqualTo("0")

            // A value planted outside the index is not listed: nothing scans the keyspace any more.
            cmd("HSET", "approval-v2:openbank-interest-service:planted", "status", "PENDING")
            assertThat(store.findPending(100).map { it.id }).containsExactly(pending.id)
        }

    @Test
    fun `a decided approval leaves the pending index`(): Unit = runBlocking {
        val store = newStore()
        val pending = store.create("interest.create", null, "maker-1")
        store.decide(pending.id, "checker-1", approve = true)

        assertThat(store.findPending(100)).isEmpty()
        assertThat(cmd("ZCARD", "approval-v2:svc-a:pending")).isEqualTo("0")
    }

    @Test
    fun `verified maker kind survives the hash and decision transitions`(): Unit = runBlocking {
        val store = newStore()
        val pending = store.create(
            "agent.propose",
            "case-1",
            "agent:reviewer",
            makerActorKind = MakerActorKind.AI_AGENT,
        )

        assertThat(cmd("HGET", "approval-v2:svc-a:${pending.id}", "actorKind")).isEqualTo("AI_AGENT")
        assertThat(store.find(pending.id)?.makerActorKind).isEqualTo(MakerActorKind.AI_AGENT)
        assertThat(store.findPending(100).single().makerActorKind).isEqualTo(MakerActorKind.AI_AGENT)
        assertThat(store.decide(pending.id, "checker-1", approve = true)?.makerActorKind)
            .isEqualTo(MakerActorKind.AI_AGENT)
        assertThat(store.markExecuted(pending.id)?.makerActorKind).isEqualTo(MakerActorKind.AI_AGENT)
    }

    @Test
    fun `a record in the previous layout is found, decided and consumed by id, without a binding`(): Unit =
        runBlocking {
            val store = newStore()
            val id = "0199a000-0000-7000-8000-000000000001"
            cmd("SET", "approval:$id", "savings.withdraw.execute|p-1|maker-1|PENDING|2026-10-01T09:00Z||", "EX", "3600")

            assertThat(store.find(id)?.status).isEqualTo(ApprovalStatus.PENDING)
            assertThat(store.find(id)?.makerActorKind).isEqualTo(MakerActorKind.UNKNOWN)
            assertThat(store.findPending(100)).`as`("previous-layout records are not listed").isEmpty()

            val decided = store.decide(id, "checker-1", approve = true)
            assertThat(decided?.status).isEqualTo(ApprovalStatus.APPROVED)
            assertThat(decided?.requestFingerprint).isNull()
            assertThat(decided?.makerActorKind).isEqualTo(MakerActorKind.UNKNOWN)
            assertThat(cmd("EXISTS", "approval:$id")).isEqualTo("0")
            assertThat(cmd("TTL", "approval-v2:svc-a:$id")!!.toLong()).isPositive()

            assertThat(store.markExecuted(id)?.status).isEqualTo(ApprovalStatus.EXECUTED)
        }

    @Test
    fun `an id that is not an approval id is never looked up`(): Unit = runBlocking {
        val store = newStore()
        store.create("interest.create", null, "maker-1")

        assertThat(store.find("pending")).isNull()
        assertThat(store.find("*")).isNull()
        assertThat(store.decide("pending", "checker-1", approve = true)).isNull()
    }

    /** The default-namespace store the #11770 cases below run against. */
    private val store by lazy { newStore() }

    private suspend fun ttl(id: String) = ds.execute("TTL", "$NS_KEY$id").awaitSuspending()!!.toLong()

    /**
     * A data source whose first write command per racer (the `EVAL` of a transition script, or the
     * `SET`/`HSET` of a read-then-write implementation) only proceeds once [parties] callers have
     * issued one — forcing every racer to finish reading the pre-transition record before any of
     * them writes. Reads pass straight through.
     */
    private fun writeBarrier(parties: Int): ReactiveRedisDataSource {
        val arrived = AtomicInteger()
        val gate = CompletableFuture<Unit>()
        val wrapped = mockk<ReactiveRedisDataSource>()
        every { wrapped.hash(String::class.java) } returns ds.hash(String::class.java)
        every { wrapped.value(String::class.java) } returns ds.value(String::class.java)
        every { wrapped.key(String::class.java) } returns ds.key(String::class.java)
        every { wrapped.execute(any<String>(), *anyVararg()) } answers {
            val command = firstArg<String>()
            val args = secondArg<Array<String>>()
            if (command in WRITES && !gate.isDone) {
                if (arrived.incrementAndGet() >= parties) gate.complete(Unit)
                Uni.createFrom().completionStage(gate).chain { _ -> ds.execute(command, *args) }
            } else {
                ds.execute(command, *args)
            }
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
        assertThat(ds.execute("EXISTS", "${NS_KEY}missing").awaitSuspending()!!.toInteger()).isZero()
        assertThat(ds.execute("EXISTS", "approval:missing").awaitSuspending()!!.toInteger()).isZero()
    }

    @Test
    fun `racing checkers - exactly one decision is acknowledged and it is the one stored`(): Unit = runBlocking {
        val created = store.create("ledger.post", "acc-1", "maker")
        val racing = RedisApprovalStore(writeBarrier(RACE), clock, NS, POOL_LIMIT)

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
        val racing = RedisApprovalStore(writeBarrier(RACE), clock, NS, POOL_LIMIT)

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
            every { evicting.hash(String::class.java) } returns ds.hash(String::class.java)
            every { evicting.value(String::class.java) } returns ds.value(String::class.java)
            every { evicting.key(String::class.java) } returns ds.key(String::class.java)
            every { evicting.execute(any<String>(), *anyVararg()) } answers {
                val command = firstArg<String>()
                val argv = secondArg<Array<String>>()
                // EVAL <script> <numkeys> <item key> ...: delete the item just before the write.
                if (command == "EVAL") {
                    ds.execute("DEL", argv[2]).chain { _ -> ds.execute(command, *argv) }
                } else {
                    ds.execute(command, *argv)
                }
            }
            val late = RedisApprovalStore(evicting, clock, NS, POOL_LIMIT)

            val result = if (approved) late.markExecuted(created.id) else late.decide(created.id, "checker", true)

            assertThat(result).isNull()
            assertThat(store.find(created.id)).isNull()
        }
    }

    private companion object {
        const val RACE = 8
        const val NS = "svc-a"
        const val NS_KEY = "approval-v2:$NS:"
        const val POOL_LIMIT = 20
        val WRITES = setOf("EVAL", "EVALSHA", "SET", "HSET")
    }
}
