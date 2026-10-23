// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.reference

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.treasury.application.port.out.ReferenceCommandKey
import com.openbank.treasury.application.port.out.ReferenceRepository
import com.openbank.treasury.application.port.out.ReferenceWrite
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.reference.CounterpartyPayload
import com.openbank.treasury.domain.reference.ReferenceKind
import com.openbank.treasury.domain.reference.ReferenceRecord
import com.openbank.treasury.domain.reference.ReferenceRef
import com.openbank.treasury.domain.reference.ReferenceStatus
import com.openbank.treasury.infrastructure.persistence.entity.CounterpartyEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import java.time.Clock
import java.time.LocalDate

@ApplicationScoped
class ReferenceCommandPanacheRepository :
    io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase<ReferenceCommandEntity, String>

@ApplicationScoped
class CounterpartyProjectionRepository : PanacheRepository<CounterpartyEntity>

/**
 * The reference store. Writes lock the rows they rewrite (`SELECT ... FOR UPDATE`) and compare the
 * stored status with the one the caller decided against, so two approvers racing on overlapping
 * versions cannot both win. An ACTIVE counterparty record is projected onto `counterparties` in the
 * same transaction (deals keep their foreign key to it).
 */
// TooManyFunctions: the port's six operations plus the row mapping and the projection, one adapter.
@Suppress("TooManyFunctions")
@ApplicationScoped
class ReferenceRepositoryImpl(
    private val codec: ReferencePayloadCodec,
    private val commands: ReferenceCommandPanacheRepository,
    private val projection: CounterpartyProjectionRepository,
    private val clock: Clock,
) : ReferenceRepository,
    PanacheRepository<ReferenceRecordEntity> {

    override suspend fun list(kind: ReferenceKind, status: ReferenceStatus?, asOf: LocalDate?): List<ReferenceRecord> {
        val rows = Panache.withSession {
            if (status == null) {
                find("kind = ?1 order by recordKey asc, version asc", kind.wire).list()
            } else {
                find("kind = ?1 and status = ?2 order by recordKey asc, version asc", kind.wire, status.name).list()
            }
        }.awaitSuspending()
        val records = rows.map { it.toDomain() }
        return if (asOf == null) records else records.filter { it.isEffectiveOn(asOf) }
    }

    override suspend fun versions(kind: ReferenceKind, key: String): List<ReferenceRecord> = Panache.withSession {
        find("kind = ?1 and recordKey = ?2 order by version asc", kind.wire, key).list()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun live(): List<ReferenceRecord> = Panache.withSession {
        find("status in ?1", listOf(ReferenceStatus.PENDING.name, ReferenceStatus.ACTIVE.name)).list()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun notEffective(refs: Collection<ReferenceRef>, on: LocalDate): List<ReferenceRef> {
        if (refs.isEmpty()) return emptyList()
        val active = Panache.withSession {
            find(
                "status = ?1 and kind in ?2 and recordKey in ?3",
                ReferenceStatus.ACTIVE.name,
                refs.map { it.kind.wire }.distinct(),
                refs.map { it.key }.distinct(),
            ).list()
        }.awaitSuspending().map { it.toDomain() }
        return refs.filter { ref -> active.none { it.ref == ref && it.isEffectiveOn(on) } }
    }

    override suspend fun save(writes: List<ReferenceWrite>, command: ReferenceCommandKey?) {
        val today = AccountingClock.bank(clock).today()
        Panache.withTransaction {
            writes.fold(Uni.createFrom().voidItem()) { chain, w -> chain.flatMap { write(w) } }
                .flatMap {
                    writes.map { it.record }
                        .filter { it.kind == ReferenceKind.COUNTERPARTY && it.status == ReferenceStatus.ACTIVE }
                        .fold(Uni.createFrom().voidItem()) { chain, r -> chain.flatMap { project(r, today) } }
                }
                .flatMap { command?.let { persistCommand(it) } ?: Uni.createFrom().voidItem() }
        }.awaitSuspending()
    }

    override suspend fun decide(
        kind: ReferenceKind,
        key: String,
        command: ReferenceCommandKey,
        decide: (List<ReferenceRecord>) -> List<ReferenceWrite>,
    ): List<ReferenceRecord> {
        val today = AccountingClock.bank(clock).today()
        return Panache.withTransaction {
            find("kind = ?1 and recordKey = ?2 order by version asc", kind.wire, key)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .list()
                .flatMap { rows ->
                    val locked = rows.map { it.toDomain() }
                    val writes = decide(locked)
                    writes.forEach { w ->
                        val row = checkNotNull(rows.firstOrNull { it.version == w.record.version }) {
                            "reference ${w.record.ref} version ${w.record.version} disappeared"
                        }
                        val prior = locked.first { it.version == w.record.version }
                        check(w.priorStatus != null && prior.status == w.priorStatus) {
                            "reference ${w.record.ref} version ${w.record.version} is now ${prior.status}, not ${w.priorStatus}; reload and retry"
                        }
                        row.fill(w.record)
                    }
                    writes.map { it.record }
                        .filter { it.kind == ReferenceKind.COUNTERPARTY && it.status == ReferenceStatus.ACTIVE }
                        .fold(Uni.createFrom().voidItem()) { chain, r -> chain.flatMap { project(r, today) } }
                        .flatMap { persistCommand(command) }
                        .map { writes.map { it.record } }
                }
        }.awaitSuspending()
    }

    private fun write(w: ReferenceWrite): Uni<Void> {
        val r = w.record
        return find("kind = ?1 and recordKey = ?2 and version = ?3", r.kind.wire, r.key, r.version)
            .withLock(LockModeType.PESSIMISTIC_WRITE)
            .firstResult()
            .flatMap { existing ->
                if (w.priorStatus == null) {
                    check(existing == null) {
                        "reference ${r.ref} version ${r.version} already exists; retry the proposal"
                    }
                    persist(ReferenceRecordEntity().fill(r)).replaceWithVoid()
                } else {
                    checkNotNull(existing) { "reference ${r.ref} version ${r.version} disappeared" }
                    check(existing.status == w.priorStatus.name) {
                        "reference ${r.ref} version ${r.version} is now ${existing.status}, not ${w.priorStatus}; reload and retry"
                    }
                    // A managed entity: the change flushes with the transaction.
                    existing.fill(r)
                    Uni.createFrom().voidItem()
                }
            }
    }

    /** Insert the projection row a deal's foreign key needs; refresh it while the record is effective today. */
    private fun project(record: ReferenceRecord, today: LocalDate): Uni<Void> {
        val p = record.payload as CounterpartyPayload
        return projection.find("counterpartyId", p.id).firstResult().flatMap { row ->
            val effective = record.isEffectiveOn(today)
            when {
                row == null -> projection.persist(
                    CounterpartyEntity().apply {
                        counterpartyId = p.id
                        synthetic = false
                        fillFrom(p)
                    },
                ).replaceWithVoid()
                effective -> {
                    row.fillFrom(p)
                    Uni.createFrom().voidItem()
                }
                else -> Uni.createFrom().voidItem()
            }
        }
    }

    private fun CounterpartyEntity.fillFrom(p: CounterpartyPayload) {
        name = p.name
        kind = p.kind.name
        limitCzk = p.limits[Deal.CZK]
        limitEur = p.limits[Deal.EUR]
    }

    private fun persistCommand(c: ReferenceCommandKey): Uni<Void> = commands.persist(
        ReferenceCommandEntity().apply {
            idempotencyKey = c.key
            action = c.action
            kind = c.kind.wire
            recordKey = c.recordKey
            version = c.version
            createdAt = clock.instant()
        },
    ).replaceWithVoid()

    override suspend fun findCommand(key: String): ReferenceCommandKey? = Panache.withSession {
        commands.findById(key)
    }.awaitSuspending()?.let {
        ReferenceCommandKey(it.idempotencyKey, it.action, ReferenceKind.fromWire(it.kind), it.recordKey, it.version)
    }

    private fun ReferenceRecordEntity.fill(r: ReferenceRecord) = apply {
        kind = r.kind.wire
        recordKey = r.key
        version = r.version
        status = r.status.name
        validFrom = r.validFrom
        validTo = r.validTo
        verified = r.verified
        payload = codec.write(r.payload)
        createdBy = r.createdBy.id
        createdByType = r.createdBy.type.name
        createdAt = r.createdAt
        reason = r.reason
        approvedBy = r.approvedBy?.id
        approvedByType = r.approvedBy?.type?.name
        approvedAt = r.approvedAt
        verifiedBy = r.verifiedBy?.id
        decisionReason = r.decisionReason
        approvalNote = r.approvalNote
    }

    private fun ReferenceRecordEntity.toDomain(): ReferenceRecord {
        val k = ReferenceKind.fromWire(kind)
        return ReferenceRecord(
            kind = k,
            key = recordKey,
            version = version,
            status = ReferenceStatus.valueOf(status),
            validFrom = validFrom,
            validTo = validTo,
            verified = verified,
            payload = codec.read(k, payload),
            createdBy = Actor(createdBy, ActorType.valueOf(createdByType)),
            createdAt = createdAt,
            reason = reason,
            approvedBy = approvedBy?.let { Actor(it, ActorType.valueOf(approvedByType ?: ActorType.HUMAN.name)) },
            approvedAt = approvedAt,
            verifiedBy = verifiedBy?.let { Actor(it, ActorType.HUMAN) },
            decisionReason = decisionReason,
            approvalNote = approvalNote,
        )
    }
}
