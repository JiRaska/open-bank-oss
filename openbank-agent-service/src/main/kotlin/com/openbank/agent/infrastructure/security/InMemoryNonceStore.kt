// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.security

import com.openbank.agent.application.port.out.NonceStore
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Default [NonceStore]: a per-pod concurrent map. Keeps the service offline-buildable/testable
 * with zero infra, and is correct as long as `replicas: 1` (every environment today). It is NOT
 * safe across replicas — see [NonceStore]'s KDoc — which is why the deployed image instead binds
 * [RedisNonceStore] via `agent.identity.svid.nonce-store=redis` (issue #4728).
 */
@ApplicationScoped
class InMemoryNonceStore : NonceStore {

    private val seen = ConcurrentHashMap<String, Instant>()

    override suspend fun claim(nonce: String, ttlSeconds: Long): Boolean {
        val now = Instant.now()
        if (seen.size > MAX_NONCES) seen.values.removeIf { it.isBefore(now) }
        val expiry = now.plusSeconds(ttlSeconds)
        return seen.putIfAbsent(nonce, expiry) == null
    }

    private companion object {
        const val MAX_NONCES = 10_000
    }
}
