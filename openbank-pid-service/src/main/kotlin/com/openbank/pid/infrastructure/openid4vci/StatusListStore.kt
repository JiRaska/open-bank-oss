// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.pid.infrastructure.openid4vci

/**
 * Storage for the Token Status List (ADR-0094): the monotonic index allocation and per-index
 * revocation state behind [StatusListService]. Split from the service so the JOSE signing /
 * encoding logic is testable without a database, and so the durable ([PostgresStatusListStore]) and
 * fast in-memory ([InMemoryStatusListStore]) backings are swappable.
 *
 * Durability is the whole point: a revocation, and the allocation counter, MUST survive a pod
 * restart and be consistent across replicas (a fail-open revocation mechanism fails eIDAS 2.0).
 */
interface StatusListStore {
    /** Reserve and return the next status index for a credential about to be issued. Strictly increasing. */
    suspend fun allocate(): Long

    /** Flip an allocated index to revoked. Returns false for an index never allocated. Idempotent. */
    suspend fun revoke(index: Long): Boolean

    /** True if [index] was allocated and is revoked. */
    suspend fun isRevoked(index: Long): Boolean

    /** All currently-revoked indices, used to (re)build the published status-list bit array. */
    suspend fun revokedIndices(): List<Long>
}
