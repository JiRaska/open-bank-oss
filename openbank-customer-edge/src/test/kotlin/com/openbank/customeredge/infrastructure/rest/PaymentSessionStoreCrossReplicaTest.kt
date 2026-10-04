// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.redis.runtime.datasource.BlockingRedisDataSourceImpl
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.redis.client.Redis
import io.vertx.mutiny.redis.client.RedisAPI
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration

/**
 * Two edge replicas, one Redis (issue #4728). Each "replica" is its own [PaymentSessionStore] over
 * its own Redis connection, exactly as two pods would be, so the only thing they can share is what
 * the backend puts in Redis. Against the old per-pod `ConcurrentHashMap` every assertion that
 * crosses from A to B fails with a null — the 404 a payer got whenever the load balancer picked the
 * other pod, and the reason customer-edge was pinned to one replica.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentSessionStoreCrossReplicaTest {

    private var container: GenericContainer<*>? = null
    private val vertx: Vertx = Vertx.vertx()
    private lateinit var podA: PaymentSessionStore
    private lateinit var podB: PaymentSessionStore
    private lateinit var raw: RedisDataSource

    @BeforeAll
    fun start() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker not available")
        val c = GenericContainer(DockerImageName.parse(VALKEY_IMAGE)).withExposedPorts(REDIS_PORT)
        c.start()
        container = c
        val url = "redis://${c.host}:${c.getMappedPort(REDIS_PORT)}"
        podA = PaymentSessionStore(RedisPaymentSessionBackend(dataSource(url)))
        podB = PaymentSessionStore(RedisPaymentSessionBackend(dataSource(url)))
        raw = dataSource(url)
    }

    @AfterAll
    fun stop() {
        container?.stop()
        vertx.closeAndAwait()
    }

    private fun dataSource(url: String): RedisDataSource {
        val redis = Redis.createClient(vertx, url)
        return BlockingRedisDataSourceImpl(vertx, redis, RedisAPI.api(redis), Duration.ofSeconds(5))
    }

    private fun createOn(store: PaymentSessionStore, amount: String? = "250.00") = store.create(
        creditorAccountId = "acc-receiver",
        creditorPartyId = "party-receiver",
        displayName = "Jana N.",
        requestedAmount = amount,
        creditorMasked = "CZ…6789",
    )

    @Test
    fun `a session created on one replica resolves on the other with every field intact`() {
        val token = createOn(podA)

        val onB = podB.resolve(token)

        assertThat(onB).isNotNull
        assertThat(onB!!.creditorAccountId).isEqualTo("acc-receiver")
        assertThat(onB.creditorPartyId).isEqualTo("party-receiver")
        assertThat(onB.displayName).isEqualTo("Jana N.")
        assertThat(onB.requestedAmount).isEqualTo("250.00")
        assertThat(onB.creditorMasked).isEqualTo("CZ…6789")
        assertThat(onB.paid).isFalse()
        assertThat(onB.paymentId).isNull()
        assertThat(onB).isEqualTo(podA.resolve(token))
    }

    @Test
    fun `an open-amount session keeps its null amount across replicas`() {
        val token = createOn(podA, amount = null)

        assertThat(podB.resolve(token)!!.requestedAmount).isNull()
    }

    @Test
    fun `payment attached and settled on one replica is what the receiver polls on the other`() {
        val token = createOn(podA)

        podB.attachPayment(token, "pay-1")
        podB.markPaid(token)

        val polled = podA.resolve(token)!!
        assertThat(polled.paymentId).isEqualTo("pay-1")
        assertThat(polled.paid).isTrue()
    }

    @Test
    fun `first payment id wins even when the second write comes from another replica`() {
        val token = createOn(podA)

        podA.attachPayment(token, "pay-first")
        podB.attachPayment(token, "pay-second")

        assertThat(podB.resolve(token)!!.paymentId).isEqualTo("pay-first")
    }

    @Test
    fun `the session carries a TTL no longer than the store's own`() {
        val token = createOn(podA)

        val pttl = raw.key(String::class.java).pttl(RedisPaymentSessionBackend.key(token))

        assertThat(pttl).isBetween(1L, PaymentSessionStore.TTL_MS)
    }

    @Test
    fun `updates to an expired or unknown token never resurrect it as a TTL-less key`() {
        val token = "0".repeat(32)

        podA.attachPayment(token, "pay-late")
        podB.markPaid(token)

        assertThat(raw.key(String::class.java).exists(RedisPaymentSessionBackend.key(token))).isFalse()
        assertThat(podA.resolve(token)).isNull()
    }

    @Test
    fun `an unknown token is null on every replica`() {
        assertThat(podA.resolve("does-not-exist")).isNull()
        assertThat(podB.resolve("does-not-exist")).isNull()
    }

    private companion object {
        // Same Valkey line the edge runs in the cluster (redis.yaml).
        const val VALKEY_IMAGE = "docker.io/valkey/valkey:8.1.10-alpine"
        const val REDIS_PORT = 6379
    }
}
