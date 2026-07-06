// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.infrastructure.persistence

import com.openbank.audit.application.port.out.SessionLogRepositoryPort
import com.openbank.audit.domain.model.SessionLogEntry
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "session_logs")
class SessionLogEntity : PanacheEntity() {
    @Column(name = "log_id", nullable = false, unique = true)
    lateinit var logId: UUID

    @Column(name = "party_id")
    var partyId: UUID? = null

    @Column(name = "session_id", nullable = false)
    lateinit var sessionId: String

    @Column(name = "actor_id")
    var actorId: String? = null

    @Column(name = "event_type", nullable = false)
    lateinit var eventType: String

    @Column(name = "ip_address")
    var ipAddress: String? = null

    @Column(name = "user_agent")
    var userAgent: String? = null

    @Column(name = "occurred_at", nullable = false)
    lateinit var occurredAt: Instant
}

/**
 * Persistence adapter for [SessionLogRepositoryPort] (ADR-0118 §2/§5, issue #268).
 *
 * Unlike [AuditRepository], `session_logs` carries no immutability RULE and no hash chain —
 * it is a deliberately prunable, low-value operational log, so [deleteOlderThan] performs a
 * real DELETE (see V7 migration comment for why this table exists separately from
 * `audit_entries`).
 */
@ApplicationScoped
class SessionLogRepository :
    PanacheRepository<SessionLogEntity>,
    SessionLogRepositoryPort {

    override suspend fun save(entry: SessionLogEntry) {
        val e = SessionLogEntity().also {
            it.logId = entry.id
            it.partyId = entry.partyId
            it.sessionId = entry.sessionId
            it.actorId = entry.actorId
            it.eventType = entry.eventType
            it.ipAddress = entry.ipAddress
            it.userAgent = entry.userAgent
            it.occurredAt = entry.occurredAt
        }
        Panache.withTransaction { persist(e) }.awaitSuspending()
    }

    override suspend fun deleteOlderThan(cutoff: Instant): Long = Panache.withTransaction {
        delete("occurredAt < ?1", cutoff)
    }.awaitSuspending().toLong()

    override suspend fun countOlderThan(cutoff: Instant): Long = Panache.withSession {
        count("occurredAt < ?1", cutoff)
    }.awaitSuspending()
}
