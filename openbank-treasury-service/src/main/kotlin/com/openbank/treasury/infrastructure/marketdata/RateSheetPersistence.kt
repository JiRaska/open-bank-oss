// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.marketdata

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.treasury.domain.marketdata.RateSheet
import com.openbank.treasury.domain.marketdata.RateSheetRate
import com.openbank.treasury.domain.marketdata.RateSheetStatus
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorType
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "internal_rate_sheets")
class RateSheetEntity : PanacheEntityBase {
    @Id
    @Column(name = "sheet_id", nullable = false)
    lateinit var sheetId: UUID

    @Column(name = "name", nullable = false)
    lateinit var name: String

    @Column(name = "effective_from", nullable = false)
    lateinit var effectiveFrom: LocalDate

    @Column(name = "rates_json", nullable = false)
    lateinit var ratesJson: String

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "proposed_by", nullable = false)
    lateinit var proposedBy: String

    @Column(name = "proposed_by_type", nullable = false)
    lateinit var proposedByType: String

    @Column(name = "proposed_at", nullable = false)
    lateinit var proposedAt: Instant

    @Column(name = "decided_by")
    var decidedBy: String? = null

    @Column(name = "decided_at")
    var decidedAt: Instant? = null

    @Column(name = "decision_reason")
    var decisionReason: String? = null

    @Version
    @Column(name = "row_version", nullable = false)
    var rowVersion: Long = 0
}

@ApplicationScoped
class RateSheetPanacheRepository : PanacheRepositoryBase<RateSheetEntity, UUID>

/** No rate sheet with that id: 404. */
class RateSheetNotFoundException(message: String) : RuntimeException(message)

/** Stale write on a rate sheet (a concurrent decision): 409 on the caller's side. */
class RateSheetStaleException(message: String) : IllegalStateException(message)

/**
 * Contract 8.5 rate-sheet store. A decision locks the sheet row, re-checks it is still PENDING and,
 * when approving, supersedes the ACTIVE sheet of the same name in the same transaction (a partial
 * unique index allows at most one ACTIVE per name).
 */
@ApplicationScoped
class RateSheetStore(private val rows: RateSheetPanacheRepository, private val objectMapper: ObjectMapper) {

    suspend fun list(status: RateSheetStatus?, limit: Int): List<RateSheet> = Panache.withSession {
        if (status == null) {
            rows.find("order by proposedAt desc").page(0, limit).list()
        } else {
            rows.find("status = ?1 order by proposedAt desc", status.name).page(0, limit).list()
        }
    }.awaitSuspending().map { it.toDomain() }

    suspend fun find(id: UUID): RateSheet? = Panache.withSession { rows.findById(id) }.awaitSuspending()?.toDomain()

    suspend fun propose(sheet: RateSheet) {
        Panache.withTransaction { rows.persist(sheet.toEntity()) }.awaitSuspending()
    }

    /** Apply [decide] to the PENDING sheet under a row lock; approving supersedes the ACTIVE one of the name. */
    suspend fun decide(id: UUID, decide: (RateSheet) -> RateSheet): RateSheet = Panache.withTransaction {
        rows.find("sheetId", id).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult().flatMap { row ->
            val e = row ?: throw RateSheetNotFoundException("rate sheet $id not found")
            if (e.status != RateSheetStatus.PENDING.name) throw RateSheetStaleException("rate sheet $id is ${e.status}")
            val decided = decide(e.toDomain())
            val supersede: Uni<*> = if (decided.status == RateSheetStatus.ACTIVE) {
                rows.update(
                    "status = ?1 where name = ?2 and status = ?3 and sheetId <> ?4",
                    RateSheetStatus.SUPERSEDED.name,
                    decided.name,
                    RateSheetStatus.ACTIVE.name,
                    id,
                ).flatMap { Panache.getSession().flatMap { s -> s.flush() } }
            } else {
                Uni.createFrom().voidItem()
            }
            supersede.map {
                e.status = decided.status.name
                e.decidedBy = decided.decidedBy?.id
                e.decidedAt = decided.decidedAt
                e.decisionReason = decided.decisionReason
                decided
            }
        }
    }.awaitSuspending()

    private fun RateSheet.toEntity() = RateSheetEntity().also {
        it.sheetId = id
        it.name = name
        it.effectiveFrom = effectiveFrom
        it.ratesJson = objectMapper.writeValueAsString(rates)
        it.status = status.name
        it.proposedBy = proposedBy.id
        it.proposedByType = proposedBy.type.name
        it.proposedAt = proposedAt
    }

    private fun RateSheetEntity.toDomain() = RateSheet(
        id = sheetId,
        name = name,
        effectiveFrom = effectiveFrom,
        rates = objectMapper.readValue<List<RateSheetRate>>(ratesJson),
        status = RateSheetStatus.valueOf(status),
        proposedBy = Actor(proposedBy, ActorType.valueOf(proposedByType)),
        proposedAt = proposedAt,
        decidedBy = decidedBy?.let { Actor(it, ActorType.HUMAN) },
        decidedAt = decidedAt,
        decisionReason = decisionReason,
    )
}
