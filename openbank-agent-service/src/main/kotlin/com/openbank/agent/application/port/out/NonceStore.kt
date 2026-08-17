// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application.port.out

/**
 * Single-use claim for a [AgentSvidVerifier][com.openbank.agent.application.AgentSvidVerifier]
 * PoP nonce (ADR-0031 D3b, issue #4728).
 *
 * The replay guard's entire security property is that a nonce can be claimed **once**: under N
 * replicas, a store whose state is per-pod (a bare `ConcurrentHashMap`) lets a replayed PoP
 * succeed simply by landing on a different pod — the guard weakens in proportion to replica
 * count rather than failing loudly. [claim] must therefore be atomic and, in any deployment with
 * more than one replica, backed by a store all replicas share.
 *
 * The default binding [com.openbank.agent.infrastructure.security.InMemoryNonceStore] keeps the
 * service offline-buildable/testable with zero infra (correct only at `replicas: 1`, which is
 * what every environment runs today). The durable, cross-replica binding is
 * [com.openbank.agent.infrastructure.security.RedisNonceStore], the same
 * `quarkus-redis-client` + per-service `@Produces`-free `@Alternative` mechanism already used
 * fleet-wide for TTL-bounded shared state (`RedisIdempotencyStore`, `RedisApprovalStore` in
 * `openbank-libs-runtime`), activated by `agent.identity.svid.nonce-store=redis`.
 */
interface NonceStore {
    /**
     * Atomically claims [nonce] for [ttlSeconds]. Returns `true` the first time a given [nonce]
     * is claimed (the caller may proceed), `false` on every subsequent claim within [ttlSeconds]
     * (a replay — the caller must reject).
     */
    suspend fun claim(nonce: String, ttlSeconds: Long): Boolean
}
