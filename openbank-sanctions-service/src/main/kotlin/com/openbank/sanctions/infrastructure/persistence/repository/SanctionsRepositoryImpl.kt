// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.persistence.repository

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.sanctions.application.port.out.SanctionsOutboxRepository
import com.openbank.sanctions.application.port.out.SanctionsRepository
import com.openbank.sanctions.domain.model.*
import com.openbank.sanctions.infrastructure.persistence.entity.SanctionsCheckEntity
import com.openbank.sanctions.infrastructure.persistence.mapper.*
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.exception.ConstraintViolationException
import java.util.UUID

@ApplicationScoped
class SanctionsRepositoryImpl(private val outboxRepo: SanctionsOutboxRepository) :
    SanctionsRepository,
    PanacheRepository<SanctionsCheckEntity> {

    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    override suspend fun save(check: SanctionsCheck): SanctionsCheck {
        val e = check.toEntity()
        Panache.withTransaction { persist(e) }.awaitSuspending()
        return e.toDomain()
    }

    /**
     * Persist the check and its outbox event in one transaction, replaying idempotently when a
     * concurrent caller won the race for the same `idempotencyKey` (#3264).
     *
     * `SanctionsService.screen` already looks the key up before screening, but that is a
     * check-then-act: screening takes seconds, so a retry that arrives while the first call is
     * still in flight finds nothing, screens too, and then loses the INSERT to the unique index on
     * `idempotency_key`. Before this, that surfaced as an unhandled `ConstraintViolationException`
     * -> 500, which the caller cannot distinguish from a transport fault. Measured cost: a
     * domestic payment was held for hours on a check that had in fact completed and stored `CLEAR`.
     *
     * On that specific violation the loser's transaction is already rolled back — so it emitted no
     * duplicate outbox event — and the winner's row is the one true result for the key, so we
     * return it. Any other failure, including a primary-key violation, is rethrown untouched.
     */
    override suspend fun saveWithEvent(check: SanctionsCheck, eventType: String): SanctionsCheck {
        val e = check.toEntity()
        val event = OutboxMessage(
            aggregateId = check.id,
            eventType = eventType,
            payload = mapper.writeValueAsString(check),
        )
        return try {
            Panache.withTransaction {
                persist(e).chain { _ -> outboxRepo.persistInTransaction(event) }
            }.awaitSuspending()
            e.toDomain()
        } catch (ex: ConstraintViolationException) {
            if (!ex.isIdempotencyKeyViolation()) throw ex
            // The winner committed between our lookup and our INSERT; return its row.
            findByIdempotencyKey(check.idempotencyKey) ?: throw ex
        }
    }

    /**
     * True when this violation is the unique index on `sanctions_checks.idempotency_key`.
     *
     * Identified by constraint, never by SQLSTATE alone: a primary-key collision is also 23505,
     * and it means something entirely different — the persist-vs-merge failure mode on an
     * application-assigned `@Id` (ADR-0126 D3). Swallowing that as "an idempotent replay" would
     * hide a real defect behind a successful-looking response, so it must keep propagating.
     *
     * `constraintName` is authoritative when Hibernate populates it; the message scan is the
     * fallback for drivers that leave it null. The name comes from the `UNIQUE` column in
     * `V1__create_sanctions.sql` (Postgres derives it), so it is not independently maintained —
     * and `SanctionsIdempotentReplayIT` drives a real duplicate through Postgres, so a rename
     * fails that test instead of quietly turning this branch into dead code.
     */
    private fun ConstraintViolationException.isIdempotencyKeyViolation(): Boolean =
        constraintName == IDEMPOTENCY_KEY_CONSTRAINT ||
            generateSequence(this as Throwable) { t -> t.cause.takeIf { it !== t } }
                .any { it.message?.contains(IDEMPOTENCY_KEY_CONSTRAINT) == true }

    private companion object {
        const val IDEMPOTENCY_KEY_CONSTRAINT = "sanctions_checks_idempotency_key_key"
    }

    override suspend fun findById(id: UUID) =
        Panache.withSession { find("id", id).firstResult() }.awaitSuspending()?.toDomain()
    override suspend fun findByIdempotencyKey(key: String) =
        Panache.withSession { find("idempotencyKey", key).firstResult() }.awaitSuspending()?.toDomain()
    override suspend fun findByStatus(status: SanctionsCheckStatus) =
        Panache.withSession { find("status", status).list() }.awaitSuspending().map { it.toDomain() }
    override suspend fun listChecks(): List<SanctionsCheck> =
        Panache.withSession { listAll() }.awaitSuspending().map { it.toDomain() }
}
