// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.openid4vci

import com.openbank.pid.infrastructure.persistence.entity.StatusListEntryEntity
import io.quarkus.arc.properties.IfBuildProperty
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant

@ApplicationScoped
class StatusListEntryRepo : PanacheRepository<StatusListEntryEntity>

/**
 * Durable [StatusListStore] (ADR-0094) — the production backing. The allocation counter is the
 * `eudi_status_list_idx_seq` DB sequence (via the entity's `@GeneratedValue`), and revocation is the
 * `revoked` column, so both survive a pod restart and are correct across replicas — closing the
 * fail-open eIDAS revocation hole the in-memory store had. Every op wraps a Panache reactive session
 * ([Panache.withSession]/[withTransaction]) because these are `suspend` methods and Hibernate
 * Reactive needs an open Mutiny session on the call's context (mirrors PartyRepositoryImpl).
 *
 * Selected by `openbank.pid.eudi.persistence=postgres` (the default); otherwise [InMemoryStatusListStore].
 */
@ApplicationScoped
@IfBuildProperty(name = "openbank.pid.eudi.persistence", stringValue = "postgres")
class PostgresStatusListStore(private val repo: StatusListEntryRepo, private val clock: Clock) : StatusListStore {

    override suspend fun allocate(): Long {
        // SEQUENCE-generated id: persist assigns idx from the durable sequence (pre-insert fetch).
        val entity = StatusListEntryEntity().apply { allocatedAt = Instant.now(clock) }
        Panache.withTransaction { repo.persist(entity) }.awaitSuspending()
        return entity.idx
    }

    override suspend fun revoke(index: Long): Boolean = Panache.withTransaction {
        // 1 row ⇒ the index was allocated (now/already revoked); 0 ⇒ never allocated. Idempotent:
        // coalesce keeps the FIRST revocation timestamp on a repeat revoke.
        repo.update("revoked = true, revokedAt = coalesce(revokedAt, ?1) where idx = ?2", Instant.now(clock), index)
    }.awaitSuspending() > 0

    override suspend fun isRevoked(index: Long): Boolean = Panache.withSession {
        repo.count("idx = ?1 and revoked = true", index)
    }.awaitSuspending() > 0

    override suspend fun revokedIndices(): List<Long> = Panache.withSession {
        repo.find("revoked = true").list()
    }.awaitSuspending().map { it.idx }
}
