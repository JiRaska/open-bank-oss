// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.approval.impl

import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.ApprovalStoreContractTest
import io.quarkus.redis.runtime.datasource.ReactiveRedisDataSourceImpl
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.redis.client.Redis
import io.vertx.mutiny.redis.client.RedisAPI
import io.vertx.redis.client.RedisOptions
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
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
                .setMaxPoolSize(POOL)
                .setMaxPoolWaiting(POOL * 2),
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
    fun `a record in the previous layout is found, decided and consumed by id, without a binding`(): Unit =
        runBlocking {
            val store = newStore()
            val id = "0199a000-0000-7000-8000-000000000001"
            cmd("SET", "approval:$id", "savings.withdraw.execute|p-1|maker-1|PENDING|2026-10-01T09:00Z||", "EX", "3600")

            assertThat(store.find(id)?.status).isEqualTo(ApprovalStatus.PENDING)
            assertThat(store.findPending(100)).`as`("previous-layout records are not listed").isEmpty()

            val decided = store.decide(id, "checker-1", approve = true)
            assertThat(decided?.status).isEqualTo(ApprovalStatus.APPROVED)
            assertThat(decided?.requestFingerprint).isNull()
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

    private companion object {
        const val POOL = 20
    }
}
