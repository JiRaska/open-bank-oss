// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.security

import com.openbank.agent.application.port.out.NonceStore
import io.quarkus.arc.properties.IfBuildProperty
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.value.SetArgs
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject

/**
 * Redis/Valkey-backed [NonceStore]: the durable, cross-replica PoP replay guard (issue #4728).
 *
 * Reuses the exact `quarkus-redis-client` mechanism already deployed for ~30 other services in
 * this fleet (`RedisIdempotencyStore`, `RedisApprovalStore` in `openbank-libs-runtime` are the
 * closest siblings — a TTL-bounded value keyed by a caller-supplied token) rather than
 * introducing a new caching technology. `SET key value NX EX ttl GET` is a single atomic Redis
 * command: it claims the key only if absent, and reports whether it was already present via the
 * returned (old) value — exactly the compare-and-swap a replay guard needs, with no separate
 * lock, version column or two-step SETNX+EXPIRE race.
 *
 * It is the `@Alternative @Priority(100)` binding behind the `@Default` [InMemoryNonceStore],
 * gated at BUILD time by `agent.identity.svid.nonce-store=redis` — same convention as
 * `openbank-analytics-sink`'s `ClickHouseProposalStore` (`openbank.analytics.sink.type=clickhouse`).
 */
@ApplicationScoped
@Alternative
@Priority(RedisNonceStore.ALTERNATIVE_PRIORITY)
@IfBuildProperty(name = "agent.identity.svid.nonce-store", stringValue = "redis")
open class RedisNonceStore : NonceStore {

    @Inject
    lateinit var redis: ReactiveRedisDataSource

    private val valueCommands by lazy { redis.value(String::class.java) }

    override suspend fun claim(nonce: String, ttlSeconds: Long): Boolean {
        val previous = valueCommands
            .setGet("$KEY_PREFIX$nonce", CLAIMED, SetArgs().nx().ex(ttlSeconds))
            .awaitSuspending()
        return previous == null
    }

    companion object {
        // Matches ClickHouseProposalStore's @Alternative priority in openbank-analytics-sink —
        // no cross-bean ordering depends on the exact value, only on outranking @Default (1).
        const val ALTERNATIVE_PRIORITY = 100
        private const val KEY_PREFIX = "agent-svid-nonce:"
        private const val CLAIMED = "1"
    }
}
