// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.customer

import com.openbank.libs.security.DpopProofVerifier
import com.openbank.libs.security.Jose4jDpopProofParser
import com.openbank.libs.security.ReplayClaim
import com.openbank.mcp.TestJsonWebToken
import com.openbank.mcp.infrastructure.customer.CustomerChannelAdmission.Outcome
import io.mockk.every
import io.mockk.mockk
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.smallrye.mutiny.Uni
import io.vertx.mutiny.redis.client.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * A Valkey that answers, but with something that is not the reply the command promises (review of
 * #16791): every such reply must be a store failure (503), never a parse exception escaping as a 500.
 * Guard-removal proof (PR evidence): moving `parse` back outside the try in `ValkeyCommands.execute`
 * turns these red.
 */
class ValkeyCustomerStoresTest {
    private val odd = mockk<Response> {
        every { toLong() } throws NumberFormatException("not an integer")
        every { this@mockk.toString() } returns "WAT"
    }
    private val ds = mockk<ReactiveRedisDataSource> {
        every { execute(any<String>(), *anyVararg()) } returns Uni.createFrom().item(odd)
    }
    private val hasher = ValkeyKeyHasher("0123456789abcdef0123456789abcdef-test-only")
    private val timeout = Duration.ofSeconds(1)
    private val store = ValkeyDpopReplayStore(ds, timeout, hasher)
    private val throttles = ValkeyCustomerThrottles(
        ds,
        timeout,
        ValkeyCustomerThrottles.Limits(10, 100, 10),
        Clock.systemUTC(),
        hasher,
    )

    @Test
    fun `an odd reply to the peek or a counter is a store failure, not a parse exception`() {
        assertThatThrownBy { runBlocking { store.isUsed("jkt", "jti", Instant.now()) } }
            .isInstanceOf(SharedStoreUnavailableException::class.java)
        assertThatThrownBy { runBlocking { throttles.proofKey("jkt") } }
            .isInstanceOf(SharedStoreUnavailableException::class.java)
    }

    @Test
    fun `an odd reply to the claim is UNAVAILABLE, never FIRST_USE`() {
        assertThat(runBlocking { store.firstUse("jkt", "jti", timeout, Instant.now()) })
            .isEqualTo(ReplayClaim.UNAVAILABLE)
    }

    @Test
    fun `through admission an odd reply is a 503 replay_store_unavailable`() {
        val key = DpopTestProofs.newKey()
        val admission = CustomerChannelAdmission(
            DpopTestProofs.ISSUER,
            McpClientAllowlist.parse(javaClass.classLoader.getResource("test-mcp-client-allowlist.yaml")!!.readText()),
            DpopProofVerifier(Jose4jDpopProofParser(), store),
            Clock.systemUTC(),
        )
        val token = TestJsonWebToken(
            mapOf(
                "iss" to DpopTestProofs.ISSUER,
                "sub" to "c0ffee00-0000-0000-0000-000000000001",
                "azp" to "mcp-local-test",
                "aud" to "openbank-mcp-service",
                "cnf" to mapOf("jkt" to DpopTestProofs.jkt(key)),
            ),
        )
        val outcome = runBlocking {
            admission.admit(token, "DPoP ${DpopTestProofs.ACCESS_TOKEN}", listOf(DpopTestProofs.proof(key)), throttles)
        }
        assertThat(outcome).isInstanceOf(Outcome.Refused::class.java)
        assertThat((outcome as Outcome.Refused).status).isEqualTo(503)
        assertThat(outcome.reason).isEqualTo(CustomerChannelAdmission.REPLAY_STORE_UNAVAILABLE)
    }

    @Test
    fun `the key hasher is keyed, length-prefixed and refuses a short secret`() {
        val other = ValkeyKeyHasher("fedcba9876543210fedcba9876543210-test-only")
        assertThat(hasher.hash("s")).isNotEqualTo(other.hash("s")).hasSize(64)
        assertThat(hasher.hash("a|b", "c")).isNotEqualTo(hasher.hash("a", "b|c"))
        assertThatThrownBy { ValkeyKeyHasher("short") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
