// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.security

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The single-replica replay defence behind the SVID nonce check: a nonce is claimable exactly
 * once. The store is only correct at `replicas: 1` (#4728) — what it must never do is let the
 * same nonce through twice within one pod.
 */
class InMemoryNonceStoreTest {

    private val store = InMemoryNonceStore()

    @Test
    fun `the first claim wins and every replay of the same nonce is refused`(): Unit = runBlocking {
        assertThat(store.claim("nonce-1", 60)).isTrue()
        assertThat(store.claim("nonce-1", 60)).isFalse()
        assertThat(store.claim("nonce-1", 60)).isFalse()
    }

    @Test
    fun `distinct nonces do not interfere`(): Unit = runBlocking {
        assertThat(store.claim("a", 60)).isTrue()
        assertThat(store.claim("b", 60)).isTrue()
        assertThat(store.claim("a", 60)).isFalse()
    }

    @Test
    fun `a zero or negative TTL still burns the nonce - an expired claim is not a free replay`(): Unit =
        runBlocking {
            assertThat(store.claim("expired", 0)).isTrue()
            assertThat(store.claim("expired", 0)).isFalse()
            assertThat(store.claim("past", -30)).isTrue()
            assertThat(store.claim("past", -30)).isFalse()
        }

    @Test
    fun `the eviction sweep past the cap does not resurrect a live nonce`(): Unit = runBlocking {
        assertThat(store.claim("live", 3600)).isTrue()
        // Push the map past MAX_NONCES with already-expired entries so the sweep runs.
        repeat(10_050) { assertThat(store.claim("stale-$it", -1)).isTrue() }

        assertThat(store.claim("live", 3600))
            .describedAs("a long-TTL nonce must survive the eviction of expired ones")
            .isFalse()
    }
}
