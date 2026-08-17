// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.security

import com.openbank.agent.application.port.out.NonceStore
import io.mockk.every
import io.mockk.mockk
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.value.ReactiveValueCommands
import io.quarkus.redis.datasource.value.SetArgs
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Proves the cross-replica half of issue #4728: a nonce claimed on one pod must be visible to a
 * sibling pod so a replayed PoP is rejected everywhere, not just on the pod that saw it first.
 *
 * WHAT THIS TEST IS SIMULATING
 *
 * Two independently-constructed [NonceStore] instances stand in for two `AgentSvidVerifier`
 * beans running in two different pods. Real infra (a shared Redis/Valkey `Deployment` per
 * namespace — `openbank-infra/gitops/components/agent/redis.yaml`) is stood in for by a single
 * `backing` map that both [RedisNonceStore] instances read and write, exactly the way both pods
 * would really talk to the one Redis instance. A Testcontainers Redis would prove the same thing
 * with a live network hop, but not exercise the wiring any more than this does — the property
 * under test is "two instances observe the same underlying store", and a mocked
 * [ReactiveRedisDataSource] backed by a real shared map demonstrates exactly that (the same
 * technique `RedisApprovalStoreTest`/`RedisIdempotencyStoreTest` in `openbank-libs-runtime` use
 * to avoid a Docker dependency for the only test of a security invariant).
 *
 * RED ON [InMemoryNonceStore]
 *
 * Swap [newRedisStore] for two *separate* [InMemoryNonceStore] instances in the first test and it
 * goes red — each pod's `ConcurrentHashMap` only ever sees its own claims, so pod B accepts the
 * exact nonce pod A already consumed. That is the bug this issue describes; the second test pins
 * it down explicitly as the contrasting case.
 */
class NonceStoreCrossReplicaTest {

    private fun newRedisStore(backing: MutableMap<String, String>): NonceStore {
        val values = mockk<ReactiveValueCommands<String, String>>()
        every { values.setGet(any<String>(), any<String>(), any<SetArgs>()) } answers {
            val key = firstArg<String>()
            val value = secondArg<String>()
            val previous = backing[key]
            if (previous == null) backing[key] = value
            Uni.createFrom().item(previous)
        }
        val redisDataSource = mockk<ReactiveRedisDataSource>()
        every { redisDataSource.value(String::class.java) } returns values
        return RedisNonceStore().apply { redis = redisDataSource }
    }

    @Test
    fun `a nonce claimed on pod A is rejected as a replay on pod B (shared Redis)`() {
        val sharedRedis = mutableMapOf<String, String>()
        val podA = newRedisStore(sharedRedis)
        val podB = newRedisStore(sharedRedis)

        val firstClaim = runBlocking { podA.claim("nonce-1", 60L) }
        val secondClaim = runBlocking { podB.claim("nonce-1", 60L) }

        assertThat(firstClaim).describedAs("pod A sees this nonce for the first time").isTrue()
        assertThat(secondClaim)
            .describedAs(
                "pod B must see pod A's claim through the shared store and refuse the replay — " +
                    "on the old per-pod ConcurrentHashMap this would wrongly be true (#4728)",
            )
            .isFalse()
    }

    @Test
    fun `contrast — two UNSHARED in-memory stores each accept the same nonce (the bug this replaces)`() {
        val podA: NonceStore = InMemoryNonceStore()
        val podB: NonceStore = InMemoryNonceStore()

        val firstClaim = runBlocking { podA.claim("nonce-1", 60L) }
        val secondClaim = runBlocking { podB.claim("nonce-1", 60L) }

        assertThat(firstClaim).isTrue()
        assertThat(secondClaim)
            .describedAs(
                "each pod's in-memory map is independent, so pod B wrongly accepts a nonce pod A " +
                    "already consumed — exactly the weakened replay guard #4728 describes, and why " +
                    "InMemoryNonceStore is only the offline-buildable default, never the deployed binding",
            )
            .isTrue()
    }
}
