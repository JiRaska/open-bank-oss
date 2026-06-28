// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.persistence.repository

import com.openbank.notification.infrastructure.persistence.entity.NotificationEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
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
}
