// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.customer

import com.openbank.libs.security.DpopPolicy
import com.openbank.libs.security.DpopProofVerifier
import com.openbank.libs.security.Jose4jDpopProofParser
import com.openbank.libs.security.ReplayClaim
import com.openbank.mcp.TestJsonWebToken
import com.openbank.mcp.infrastructure.customer.CustomerChannelAdmission.Outcome
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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The customer channel's SHARED state against a real Valkey (ADR-0383 P4, #16590). "Two pods" are
 * two independent Redis clients, two stores and two [CustomerChannelAdmission]s — nothing in memory
 * is shared between them except what Valkey holds.
 *
 * Guard-removal proofs (PR evidence): `NX` dropped from the SET makes the race test red (the cross-pod
 * replay test stays green there because the `EXISTS` peek refuses it first); `isUsed` reverted to `false` makes the peek test red; the try/catch around
 * `admitProof` removed makes the store-down test red (the exception escapes instead of a 503); the
 * Valkey throttles replaced by per-instance counters makes the shared-bucket test red.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ValkeyCustomerStoresIT {

    private val valkey: GenericContainer<*> = GenericContainer(DockerImageName.parse("valkey/valkey:7.2-alpine"))
        .withExposedPorts(6379)
    private val vertx = Vertx.vertx()
    private val clients = mutableListOf<Redis>()

    @BeforeAll
    fun start() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is required for this IT")
        valkey.start()
    }

    @AfterAll
    fun close() {
        clients.forEach { it.close() }
        vertx.closeAndAwait()
        if (valkey.isRunning) valkey.stop()
    }

    /** One "pod": its own client and connection pool to the shared Valkey (or to [url]). */
    private fun pod(url: String = "redis://${valkey.host}:${valkey.firstMappedPort}"): ReactiveRedisDataSourceImpl {
        val client = Redis.createClient(
            vertx,
            RedisOptions().setConnectionString(url).setMaxPoolSize(RACE).setMaxPoolWaiting(RACE * 2),
        )
        clients += client
        return ReactiveRedisDataSourceImpl(vertx, client, RedisAPI.api(client))
    }

    private val podA by lazy { pod() }
    private val podB by lazy { pod() }

    @BeforeEach
    fun flush(): Unit = runBlocking { podA.execute("FLUSHALL").awaitSuspending() }

    private val allowlist = McpClientAllowlist.parse(
        javaClass.classLoader.getResource("test-mcp-client-allowlist.yaml")!!.readText(),
    )
    private val key = DpopTestProofs.newKey()

    private fun admission(ds: ReactiveRedisDataSourceImpl) = CustomerChannelAdmission(
        DpopTestProofs.ISSUER,
        allowlist,
        DpopProofVerifier(Jose4jDpopProofParser(), ValkeyDpopReplayStore(ds, TIMEOUT, HASHER)),
        Clock.systemUTC(),
    )

    private fun throttles(ds: ReactiveRedisDataSourceImpl, perMinute: Int = 1000, shared: Int = 1000) =
        ValkeyCustomerThrottles(ds, TIMEOUT, ValkeyCustomerThrottles.Limits(perMinute, 10_000, shared), FIXED, HASHER)

    private fun token() = TestJsonWebToken(
        mapOf(
            "iss" to DpopTestProofs.ISSUER,
            "sub" to "c0ffee00-0000-0000-0000-000000000001",
            "azp" to "mcp-local-test",
            "aud" to "openbank-mcp-service",
            "cnf" to mapOf("jkt" to DpopTestProofs.jkt(key)),
        ),
    )

    private suspend fun CustomerChannelAdmission.admit(proof: String, t: CustomerChannelAdmission.Throttles) =
        admit(token(), "DPoP ${DpopTestProofs.ACCESS_TOKEN}", listOf(proof), t)

    @Test
    fun `a proof admitted by one pod is refused as replayed by another`(): Unit = runBlocking {
        val proof = DpopTestProofs.proof(key)

        assertThat(admission(podA).admit(proof, throttles(podA))).isInstanceOf(Outcome.Admitted::class.java)
        val second = admission(podB).admit(proof, throttles(podB))

        assertThat(second).isInstanceOf(Outcome.Refused::class.java)
        assertThat((second as Outcome.Refused).status).isEqualTo(401)
        assertThat(second.reason).contains("REPLAYED")
        // A fresh proof from the same key is still admitted on the second pod.
        assertThat(admission(podB).admit(DpopTestProofs.proof(key), throttles(podB)))
            .isInstanceOf(Outcome.Admitted::class.java)
    }

    @Test
    fun `concurrent claims of one jti across two pods admit exactly one`(): Unit = runBlocking {
        val a = ValkeyDpopReplayStore(podA, TIMEOUT, HASHER)
        val b = ValkeyDpopReplayStore(podB, TIMEOUT, HASHER)
        val jti = UUID.randomUUID().toString()
        val claims = (1..RACE).map { i ->
            async(Dispatchers.IO) { (if (i % 2 == 0) a else b).firstUse("jkt", jti, TTL, Instant.now()) }
        }.awaitAll()

        assertThat(claims.count { it == ReplayClaim.FIRST_USE }).isEqualTo(1)
        assertThat(claims.count { it == ReplayClaim.REPLAYED }).isEqualTo(RACE - 1)
    }

    @Test
    fun `the claim carries the proof window as its TTL, and the peek sees it from the other pod`(): Unit = runBlocking {
        val a = ValkeyDpopReplayStore(podA, TIMEOUT, HASHER)
        val b = ValkeyDpopReplayStore(podB, TIMEOUT, HASHER)
        val ttl = DpopPolicy().replayTtl

        assertThat(b.isUsed("jkt", "j1", Instant.now())).isFalse()
        assertThat(podA.execute("EXISTS", a.key("jkt", "j1")).awaitSuspending().toLong())
            .`as`("the peek writes nothing").isZero()
        assertThat(a.firstUse("jkt", "j1", ttl, Instant.now())).isEqualTo(ReplayClaim.FIRST_USE)
        assertThat(b.isUsed("jkt", "j1", Instant.now())).isTrue()

        val pttl = podB.execute("PTTL", a.key("jkt", "j1")).awaitSuspending().toLong()
        assertThat(pttl).isBetween(ttl.toMillis() - SLACK_MS, ttl.toMillis())
    }

    @Test
    fun `budgets are shared across pods - the limit holds for the sum, not per pod`(): Unit = runBlocking {
        val a = throttles(podA, perMinute = 3, shared = 4)
        val b = throttles(podB, perMinute = 3, shared = 4)

        val proofKey = listOf(a, b, a, b).map { it.proofKey("jkt-shared") }
        assertThat(proofKey.take(3)).containsOnlyNulls()
        assertThat(proofKey[3]).isEqualTo("rate limit exceeded")

        val subject = listOf(b, a, b, a).map { it.subject("s", "c") }
        assertThat(subject.take(3)).containsOnlyNulls()
        assertThat(subject[3]).isNotNull()

        val client = listOf(a, b, a, b, a).map { it.client("c") }
        assertThat(client.take(4)).containsOnlyNulls()
        assertThat(client[4]).isNotNull()

        // Every window counter has an expiry: no key can outlive its window.
        val keys = podA.execute("KEYS", "${ValkeyCustomerThrottles.KEY_PREFIX}*").awaitSuspending()
        assertThat(keys.size()).isPositive()
        keys.forEach { k ->
            assertThat(podA.execute("PTTL", k.toString()).awaitSuspending().toLong()).isPositive()
        }
    }

    @Test
    fun `store down - admission answers 503 and never admits`(): Unit = runBlocking {
        val down = pod("redis://127.0.0.1:1")
        val outcome = admission(down).admit(DpopTestProofs.proof(key), throttles(down))

        assertThat(outcome).isInstanceOf(Outcome.Refused::class.java)
        assertThat((outcome as Outcome.Refused).status).isEqualTo(503)
        assertThat(outcome.reason).isEqualTo(CustomerChannelAdmission.REPLAY_STORE_UNAVAILABLE)
        // The replay claim alone also fails closed: UNAVAILABLE, never FIRST_USE.
        assertThat(ValkeyDpopReplayStore(down, TIMEOUT, HASHER).firstUse("jkt", "j", TTL, Instant.now()))
            .isEqualTo(ReplayClaim.UNAVAILABLE)
    }

    @Test
    fun `replay store up but budgets down - still 503, never admitted`(): Unit = runBlocking {
        val down = pod("redis://127.0.0.1:1")
        val outcome = admission(podA).admit(DpopTestProofs.proof(key), throttles(down))

        assertThat(outcome).isInstanceOf(Outcome.Refused::class.java)
        assertThat((outcome as Outcome.Refused).status).isEqualTo(503)
        assertThat(outcome.reason).isEqualTo(CustomerChannelAdmission.REPLAY_STORE_UNAVAILABLE)
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS) // an unbounded wait must fail the test, not hang the build
    fun `a hung Valkey (container paused) answers 503 within the timeout, never admits`(): Unit = runBlocking {
        val hung = GenericContainer(DockerImageName.parse("valkey/valkey:7.2-alpine")).withExposedPorts(6379)
        hung.start()
        try {
            val ds = pod("redis://${hung.host}:${hung.firstMappedPort}")
            ds.execute("PING").awaitSuspending() // connection pooled while healthy
            DockerClientFactory.instance().client().pauseContainerCmd(hung.containerId).exec()
            val short = Duration.ofMillis(HUNG_TIMEOUT_MS)
            val admission = CustomerChannelAdmission(
                DpopTestProofs.ISSUER,
                allowlist,
                DpopProofVerifier(Jose4jDpopProofParser(), ValkeyDpopReplayStore(ds, short, HASHER)),
                Clock.systemUTC(),
            )
            val throttles =
                ValkeyCustomerThrottles(ds, short, ValkeyCustomerThrottles.Limits(1000, 10_000, 1000), FIXED, HASHER)
            val started = System.nanoTime()
            val outcome = admission.admit(DpopTestProofs.proof(key), throttles)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertThat(outcome).isInstanceOf(Outcome.Refused::class.java)
            assertThat((outcome as Outcome.Refused).status).isEqualTo(503)
            assertThat(outcome.reason).isEqualTo(CustomerChannelAdmission.REPLAY_STORE_UNAVAILABLE)
            // The peek is the first store call and fails at the timeout; nothing after it waits.
            assertThat(elapsedMs).isLessThan(HUNG_TIMEOUT_MS + HUNG_SLACK_MS)
        } finally {
            DockerClientFactory.instance().client().unpauseContainerCmd(hung.containerId).exec()
            hung.stop()
        }
    }

    @Test
    fun `no key names the customer subject or proof key in clear`(): Unit = runBlocking {
        val subject = "c0ffee00-0000-0000-0000-000000000001"
        val jkt = DpopTestProofs.jkt(key)
        assertThat(
            admission(podA).admit(DpopTestProofs.proof(key), throttles(podA)),
        ).isInstanceOf(Outcome.Admitted::class.java)
        val keys = podA.execute("KEYS", "mcp:customer:*").awaitSuspending().map { it.toString() }
        assertThat(keys).isNotEmpty()
        keys.forEach { k ->
            assertThat(k).doesNotContain(subject).doesNotContain(jkt).doesNotContain("|")
        }
    }

    private companion object {
        const val HUNG_TIMEOUT_MS = 300L
        const val HUNG_SLACK_MS = 1_500L
        const val RACE = 50
        const val SLACK_MS = 5_000L
        val TIMEOUT: Duration = Duration.ofSeconds(2)
        val HASHER = ValkeyKeyHasher("0123456789abcdef0123456789abcdef-test-only")
        val TTL: Duration = Duration.ofMinutes(5)
        val FIXED: Clock = Clock.fixed(Instant.parse("2026-11-18T10:00:30Z"), ZoneOffset.UTC)
    }
}
