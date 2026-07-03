// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.persistence.repository

import com.openbank.notification.infrastructure.persistence.entity.NotificationEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class NotificationRepository : PanacheRepository<NotificationEntity> {

    suspend fun listAll(page: Int, size: Int): List<NotificationEntity> =
        Panache.withSession { findAll().page(page, size).list() }.awaitSuspending()

    suspend fun countAll(): Long = Panache.withSession { count() }.awaitSuspending()

    suspend fun findByPartyId(partyId: UUID, page: Int, size: Int): List<NotificationEntity> =
        Panache.withSession { find("partyId", partyId).page(page, size).list() }.awaitSuspending()

    // Page + total in a SINGLE reactive session. A request must not chain two separate
    // Panache.withSession calls (list, then count): the second reuses the now-closed session
    // bound to the Vert.x context and throws — which 500'd the notification list endpoint.
    suspend fun pageByParty(partyId: UUID, page: Int, size: Int): Pair<List<NotificationEntity>, Long> =
        Panache.withSession {
            val query = find("partyId", partyId)
            query.count().flatMap { total -> query.page(page, size).list().map { items -> items to total } }
        }.awaitSuspending()

    suspend fun pageAll(page: Int, size: Int): Pair<List<NotificationEntity>, Long> = Panache.withSession {
        val query = findAll()
        query.count().flatMap { total -> query.page(page, size).list().map { items -> items to total } }
    }.awaitSuspending()

    suspend fun findById(id: UUID): NotificationEntity? =
        Panache.withSession { find("notificationId", id).firstResult() }.awaitSuspending()

    suspend fun deleteByPartyId(partyId: UUID): Long =
        Panache.withTransaction { delete("partyId", partyId) }.awaitSuspending()

    /**
     * Mark one notification read (idempotent). partyId scopes the UPDATE so the edge's
     * injected identity can never mark another party's row (IDOR guard at the data layer).
     * True when the row exists for that party (freshly marked OR already read); false = not found.
     */
    suspend fun markRead(id: UUID, partyId: UUID): Boolean {
        val updated = Panache.withTransaction {
            update(
                "readAt = ?1 where notificationId = ?2 and partyId = ?3 and readAt is null",
                Instant.now(),
                id,
                partyId,
            )
        }.awaitSuspending()
        if (updated > 0) return true
        // 0 rows: either already read (fine, idempotent) or not this party's notification.
        return Panache.withSession {
            find("notificationId = ?1 and partyId = ?2", id, partyId).count()
        }.awaitSuspending() > 0
    }

    /** Mark all of a party's unread notifications read; returns how many flipped. */
    suspend fun markAllRead(partyId: UUID): Int = Panache.withTransaction {
        update("readAt = ?1 where partyId = ?2 and readAt is null", Instant.now(), partyId)
    }.awaitSuspending()
}
