// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sepainstant.infrastructure.persistence.repository

import com.openbank.sepainstant.infrastructure.persistence.entity.SctInstOutboxEntity
import com.openbank.sepainstant.application.port.out.SctInstOutboxEntry
import com.openbank.sepainstant.application.port.out.SctInstOutboxMessage
import com.openbank.sepainstant.application.port.out.SctInstOutboxRepository
import com.openbank.sepainstant.application.port.out.SctInstOutboxStatus
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class SctInstOutboxRepositoryImpl(private val clock: Clock) :
    SctInstOutboxRepository,
    PanacheRepository<SctInstOutboxEntity> {

    fun persistInTransaction(message: SctInstOutboxMessage) = persist(message.toEntity())

    override suspend fun listProcessable(limit: Int): List<SctInstOutboxEntry> = Panache.withSession {
        find(
            "status in (?1, ?2) order by createdAt asc",
            SctInstOutboxStatus.PENDING.name,
            SctInstOutboxStatus.FAILED.name,
        ).range(0, limit.coerceAtLeast(1) - 1).list()
    }.awaitSuspending().map { it.toEntry() }

    override suspend fun countProcessable(): Long = countProcessableUni().awaitSuspending()

    override suspend fun markSent(eventId: UUID, sentAt: Instant) {
        Panache.withTransaction {
            find("eventId", eventId).firstResult().invoke { e ->
                if (e != null) {
                    e.status = SctInstOutboxStatus.SENT.name
                    e.attemptCount += 1
                    e.sentAt = sentAt
                    e.lastError = null
                    e.updatedAt = sentAt
                }
            }.replaceWith(Unit)
        }.awaitSuspending()
    }

    override suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant) {
        Panache.withTransaction {
            find("eventId", eventId).firstResult().invoke { e ->
                if (e != null) {
                    e.status = SctInstOutboxStatus.FAILED.name
                    e.attemptCount += 1
                    e.lastError = error.take(4000)
                    e.updatedAt = failedAt
                }
            }.replaceWith(Unit)
        }.awaitSuspending()
    }

    private fun SctInstOutboxMessage.toEntity() = SctInstOutboxEntity().also {
        it.eventId = eventId
        it.aggregateId = aggregateId
        it.eventType = eventType
        it.payload = payload
        it.status = SctInstOutboxStatus.PENDING.name
        it.attemptCount = 0
        it.createdAt = createdAt
        it.updatedAt = createdAt
    }

    private fun SctInstOutboxEntity.toEntry() = SctInstOutboxEntry(
        eventId = eventId,
        aggregateId = aggregateId,
        eventType = eventType,
        payload = payload,
        status = SctInstOutboxStatus.valueOf(status),
        attemptCount = attemptCount,
        createdAt = createdAt,
        updatedAt = updatedAt,
        sentAt = sentAt,
        lastError = lastError,
    )

    fun listProcessableUni(limit: Int): Uni<List<SctInstOutboxEntry>> = Panache.withSession {
        find(
            "status in (?1, ?2) order by createdAt asc",
            SctInstOutboxStatus.PENDING.name,
            SctInstOutboxStatus.FAILED.name,
        ).range(0, limit.coerceAtLeast(1) - 1).list()
    }.map { entities -> entities.map { it.toEntry() } }

    /**
     * Reactive sibling of [countProcessable] — the outbox backlog (PENDING + FAILED) as a [Uni],
     * for callers already on a Mutiny context (the backlog gauge refresh). Same status filter as
     * [listProcessableUni]; SENT rows are excluded (ADR-0077 / ADR-0079).
     */
    fun countProcessableUni(): Uni<Long> = Panache.withSession {
        count(
            "status in (?1, ?2)",
            SctInstOutboxStatus.PENDING.name,
            SctInstOutboxStatus.FAILED.name,
        )
    }

    fun markSentUni(eventId: UUID): Uni<Void> = Panache.withTransaction {
        find("eventId", eventId).firstResult().invoke { e ->
            if (e != null) {
                e.status = SctInstOutboxStatus.SENT.name
                e.attemptCount += 1
                e.sentAt = Instant.now(clock)
                e.lastError = null
                e.updatedAt = Instant.now(clock)
            }
        }.replaceWith(Unit)
    }.replaceWithVoid()

    fun markFailedUni(eventId: UUID, error: String): Uni<Void> = Panache.withTransaction {
        find("eventId", eventId).firstResult().invoke { e ->
            if (e != null) {
                e.status = SctInstOutboxStatus.FAILED.name
                e.attemptCount += 1
                e.lastError = error.take(4000)
                e.updatedAt = Instant.now(clock)
            }
        }.replaceWith(Unit)
    }.replaceWithVoid()
}
