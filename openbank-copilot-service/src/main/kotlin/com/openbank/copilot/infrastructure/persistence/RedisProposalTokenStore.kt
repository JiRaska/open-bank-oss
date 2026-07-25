// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.copilot.application.port.out.ProposalTokenStore
import com.openbank.copilot.domain.ProposalToken
import io.quarkus.arc.properties.UnlessBuildProperty
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.value.SetArgs
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.jboss.logging.Logger
import java.util.UUID

@ApplicationScoped
@UnlessBuildProperty(name = "copilot.token-store", stringValue = "memory")
class RedisProposalTokenStore @Inject constructor(
    private val redis: ReactiveRedisDataSource,
    private val mapper: ObjectMapper,
) : ProposalTokenStore {
    private val log = Logger.getLogger(RedisProposalTokenStore::class.java)
    private val values by lazy { redis.value(String::class.java) }

    override fun save(token: ProposalToken) {
        runBlocking {
            val json = mapper.writeValueAsString(token)
            val args = SetArgs().ex(ProposalTokenStore.TOKEN_TTL_SECONDS)
            values.set(key(token.id), json, args).awaitSuspending()
        }
        log.debugf(
            "RedisProposalTokenStore: saved token=%s tool=%s customer=%s ttl=%ds",
            token.id,
            token.toolName,
            token.customerId,
            ProposalTokenStore.TOKEN_TTL_SECONDS,
        )
    }

    override fun find(id: UUID): ProposalToken? {
        val json = runBlocking { values.get(key(id)).awaitSuspending() } ?: return null
        return runCatching { mapper.readValue(json, ProposalToken::class.java) }.getOrElse { e ->
            log.warnf(e, "RedisProposalTokenStore: failed to deserialise token=%s", id)
            null
        }
    }

    override fun delete(id: UUID) {
        runBlocking { values.getdel(key(id)).awaitSuspending() }
        log.debugf("RedisProposalTokenStore: deleted token=%s", id)
    }

    private fun key(id: UUID) = "copilot:proposal:$id"
}
