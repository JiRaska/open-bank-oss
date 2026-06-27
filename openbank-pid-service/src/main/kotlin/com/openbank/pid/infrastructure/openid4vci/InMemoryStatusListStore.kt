// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.openid4vci

import io.quarkus.arc.DefaultBean
import jakarta.enterprise.context.ApplicationScoped
import java.util.BitSet

/**
 * In-memory [StatusListStore] — a [BitSet] of revoked indices plus a monotonic counter, guarded by a
 * lock. This is the original behaviour: it is **not durable and not multi-replica safe** (a restart
 * resets both the counter and the revocations), so it is the test/dev fallback only. Production uses
 * [PostgresStatusListStore], selected by `openbank.pid.eudi.persistence=postgres` (the default); this
 * [DefaultBean] is active only when that is unset/overridden. Unit tests construct it directly.
 */
@ApplicationScoped
@DefaultBean
class InMemoryStatusListStore : StatusListStore {
    private val revoked = BitSet()
    private var nextIndex = 0L
    private val lock = Any()

    override suspend fun allocate(): Long = synchronized(lock) { nextIndex++ }

    override suspend fun revoke(index: Long): Boolean = synchronized(lock) {
        if (!inRange(index)) return false
        revoked.set(index.toInt())
        true
    }

    override suspend fun isRevoked(index: Long): Boolean = synchronized(lock) {
        inRange(index) && revoked.get(index.toInt())
    }

    override suspend fun revokedIndices(): List<Long> = synchronized(lock) {
        buildList {
            var i = revoked.nextSetBit(0)
            while (i >= 0) {
                add(i.toLong())
                i = revoked.nextSetBit(i + 1)
            }
        }
    }

    // BitSet indexes by Int; a status index past Int.MAX_VALUE (or unallocated) is never revoked.
    private fun inRange(index: Long): Boolean = index in 0 until nextIndex && index <= Int.MAX_VALUE.toLong()
}
