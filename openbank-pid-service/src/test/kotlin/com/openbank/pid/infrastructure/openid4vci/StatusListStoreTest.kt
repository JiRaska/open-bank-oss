// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.openid4vci

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for [InMemoryStatusListStore] (ADR-0094 Token Status List) — allocation is a
 * monotonic counter, revocation only sticks for an index that was actually allocated, and
 * [InMemoryStatusListStore.revokedIndices] reports exactly the revoked set in ascending order.
 */
class StatusListStoreTest {

    private val store = InMemoryStatusListStore()

    @Test
    fun `allocate returns a strictly increasing sequence starting at zero`(): Unit = runBlocking {
        assertThat(store.allocate()).isZero()
        assertThat(store.allocate()).isEqualTo(1L)
        assertThat(store.allocate()).isEqualTo(2L)
    }

    @Test
    fun `revoke succeeds for an allocated index and isRevoked reflects it`(): Unit = runBlocking {
        val idx = store.allocate()
        assertThat(store.isRevoked(idx)).isFalse()

        assertThat(store.revoke(idx)).isTrue()

        assertThat(store.isRevoked(idx)).isTrue()
    }

    @Test
    fun `revoke is idempotent — revoking twice still reports revoked`(): Unit = runBlocking {
        val idx = store.allocate()
        assertThat(store.revoke(idx)).isTrue()
        assertThat(store.revoke(idx)).isTrue()
        assertThat(store.isRevoked(idx)).isTrue()
    }

    @Test
    fun `revoke fails for an index that was never allocated`(): Unit = runBlocking {
        assertThat(store.revoke(42L)).isFalse()
        assertThat(store.isRevoked(42L)).isFalse()
    }

    @Test
    fun `isRevoked is false for an allocated but never-revoked index`(): Unit = runBlocking {
        val idx = store.allocate()
        assertThat(store.isRevoked(idx)).isFalse()
    }

    @Test
    fun `revokedIndices reports exactly the revoked set in ascending order`(): Unit = runBlocking {
        repeat(5) { store.allocate() } // indices 0..4
        store.revoke(3L)
        store.revoke(1L)

        assertThat(store.revokedIndices()).containsExactly(1L, 3L)
    }

    @Test
    fun `revokedIndices is empty when nothing has been revoked`(): Unit = runBlocking {
        repeat(3) { store.allocate() }
        assertThat(store.revokedIndices()).isEmpty()
    }
}
