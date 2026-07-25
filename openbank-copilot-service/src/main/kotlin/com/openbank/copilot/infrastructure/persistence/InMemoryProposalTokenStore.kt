// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.infrastructure.persistence

import com.openbank.copilot.application.port.out.ProposalTokenStore
import com.openbank.copilot.domain.ProposalToken
import io.quarkus.arc.properties.IfBuildProperty
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@ApplicationScoped
@IfBuildProperty(name = "copilot.token-store", stringValue = "memory")
class InMemoryProposalTokenStore(private val clock: Clock) : ProposalTokenStore {
    private val log = Logger.getLogger(InMemoryProposalTokenStore::class.java)
    private val store = ConcurrentHashMap<UUID, ProposalToken>()

    override fun save(token: ProposalToken) {
        evictExpired()
        store[token.id] = token
        log.debugf(
            "InMemoryProposalTokenStore: stored token=%s tool=%s customer=%s",
            token.id,
            token.toolName,
            token.customerId,
        )
    }

    override fun find(id: UUID): ProposalToken? {
        val token = store[id] ?: return null
        if (Instant.now(clock).isAfter(token.expiresAt)) {
            store.remove(id)
            log.debugf("InMemoryProposalTokenStore: token=%s expired, evicted", id)
            return null
        }
        return token
    }

    override fun delete(id: UUID) {
        store.remove(id)
        log.debugf("InMemoryProposalTokenStore: deleted token=%s", id)
    }

    private fun evictExpired() {
        val now = Instant.now(clock)
        store.entries.removeIf { now.isAfter(it.value.expiresAt) }
    }
}
