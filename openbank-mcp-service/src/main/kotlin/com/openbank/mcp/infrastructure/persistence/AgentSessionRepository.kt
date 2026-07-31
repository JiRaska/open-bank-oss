// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.persistence

import io.quarkus.hibernate.reactive.panache.kotlin.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

@ApplicationScoped
open class AgentSessionRepository : PanacheRepository<AgentSessionEntity> {

    open suspend fun save(session: AgentSessionEntity) {
        Panache.withTransaction { persist(session) }.awaitSuspending()
    }

    open suspend fun findById(id: UUID): AgentSessionEntity? =
        Panache.withSession { find("id", id).firstResult() }.awaitSuspending()

    /**
     * The live check the OBO resolver runs on every call (ADR-0224 D2): the session exists, is
     * not revoked, and has not expired. A revoked or expired session returns null — fail closed.
     */
    open suspend fun findActive(id: UUID, asOf: Instant): AgentSessionEntity? = Panache.withSession {
        find("id = ?1 and revokedAt is null and expiresAt > ?2", id, asOf).firstResult()
    }.awaitSuspending()

    open suspend fun revoke(id: UUID, asOf: Instant): Boolean {
        val session = findActive(id, asOf) ?: return false
        session.revokedAt = asOf
        // merge, not persist: the entity is detached and has an app-assigned @Id, so persist()
        // would schedule an INSERT and fail duplicate-key — the fleet's persist-vs-merge footgun.
        Panache.withTransaction { getSession().flatMap { s -> s.merge(session) } }.awaitSuspending()
        return true
    }
}
