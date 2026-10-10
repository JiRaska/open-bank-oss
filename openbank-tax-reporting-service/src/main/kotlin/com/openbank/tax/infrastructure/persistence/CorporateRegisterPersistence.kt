// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.persistence

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.tax.application.port.out.CorporateRegisterRepository
import com.openbank.tax.domain.corporate.CorporateFact
import com.openbank.tax.domain.corporate.CorporateRegisterEntry
import com.openbank.tax.domain.corporate.RegisterEntryStatus
import com.openbank.tax.domain.model.TaxConflictException
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "corporate_register_entry")
class CorporateRegisterEntryEntity : PanacheEntityBase {
    @Id
    @Column(name = "id")
    var id: UUID = Ids.newId()

    @Column(name = "entity_id", nullable = false)
    var entityId: String = ""

    @Column(name = "fact", nullable = false)
    var fact: String = ""

    @Column(name = "value", nullable = false)
    var value: BigDecimal = BigDecimal.ZERO

    @Column(name = "effective_from", nullable = false)
    var effectiveFrom: LocalDate = LocalDate.EPOCH

    @Column(name = "version", nullable = false)
    var version: Int = 1

    @Column(name = "reason", nullable = false)
    var reason: String = ""

    @Column(name = "evidence", nullable = false)
    var evidence: String = ""

    @Column(name = "proposed_by", nullable = false)
    var proposedBy: String = ""

    @Column(name = "proposed_at", nullable = false)
    var proposedAt: Instant? = null

    @Column(name = "status", nullable = false)
    var status: String = ""

    @Column(name = "decided_by")
    var decidedBy: String? = null

    @Column(name = "decided_at")
    var decidedAt: Instant? = null
}

@ApplicationScoped
class PanacheCorporateRegisterRepository :
    CorporateRegisterRepository,
    PanacheRepositoryBase<CorporateRegisterEntryEntity, UUID> {

    override suspend fun insert(entry: CorporateRegisterEntry): CorporateRegisterEntry = Panache.withTransaction {
        persist(entry.toEntity())
    }.awaitSuspending().toDomain()

    override suspend fun find(id: UUID): CorporateRegisterEntry? = Panache.withSession {
        find("id = ?1", id).firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun entries(entityId: String): List<CorporateRegisterEntry> = Panache.withSession {
        find("entityId = ?1 order by fact, effectiveFrom, version", entityId).list()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun latestVersion(entry: CorporateRegisterEntry): Int = Panache.withSession {
        find(
            "entityId = ?1 and fact = ?2 and effectiveFrom = ?3 order by version desc",
            entry.entityId,
            entry.fact.name,
            entry.effectiveFrom,
        ).firstResult()
    }.awaitSuspending()?.version ?: 0

    /** Conditional on the row still being PROPOSED, so two checkers cannot both decide it. */
    override suspend fun decide(entry: CorporateRegisterEntry): CorporateRegisterEntry = Panache.withTransaction {
        update(
            "status = ?1, decidedBy = ?2, decidedAt = ?3 where id = ?4 and status = ?5",
            entry.status.name,
            entry.decidedBy,
            entry.decidedAt,
            entry.id,
            RegisterEntryStatus.PROPOSED.name,
        ).map { updated ->
            if (updated != 1) throw TaxConflictException("register entry ${entry.id} was decided concurrently — reload")
            entry
        }
    }.awaitSuspending()

    private fun CorporateRegisterEntry.toEntity() = CorporateRegisterEntryEntity().also {
        it.id = id
        it.entityId = entityId
        it.fact = fact.name
        it.value = value
        it.effectiveFrom = effectiveFrom
        it.version = version
        it.reason = reason
        it.evidence = evidence
        it.proposedBy = proposedBy
        it.proposedAt = proposedAt
        it.status = status.name
        it.decidedBy = decidedBy
        it.decidedAt = decidedAt
    }

    private fun CorporateRegisterEntryEntity.toDomain() = CorporateRegisterEntry(
        id = id,
        entityId = entityId,
        fact = CorporateFact.valueOf(fact),
        value = value,
        effectiveFrom = effectiveFrom,
        version = version,
        reason = reason,
        evidence = evidence,
        proposedBy = proposedBy,
        proposedAt = requireNotNull(proposedAt) { "corporate_register_entry $id has no proposed_at" },
        status = RegisterEntryStatus.valueOf(status),
        decidedBy = decidedBy,
        decidedAt = decidedAt,
    )
}
