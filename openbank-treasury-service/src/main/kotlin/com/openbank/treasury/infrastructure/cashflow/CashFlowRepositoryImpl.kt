// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.cashflow

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.treasury.application.port.out.CalendarFlow
import com.openbank.treasury.application.port.out.CashFlowRepository
import com.openbank.treasury.application.port.out.CommandKey
import com.openbank.treasury.application.port.out.DealEvent
import com.openbank.treasury.application.port.out.DealVersionView
import com.openbank.treasury.application.port.out.TreasuryOutboxRepository
import com.openbank.treasury.domain.cashflow.CashFlow
import com.openbank.treasury.domain.cashflow.CashFlowConflict
import com.openbank.treasury.domain.cashflow.CashFlowOverride
import com.openbank.treasury.domain.cashflow.OverrideStatus
import com.openbank.treasury.domain.model.DealKind
import com.openbank.treasury.infrastructure.persistence.entity.DealCommandEntity
import com.openbank.treasury.infrastructure.persistence.entity.DealEntity
import com.openbank.treasury.infrastructure.persistence.repository.CashFlowConflictPanacheRepository
import com.openbank.treasury.infrastructure.persistence.repository.CashFlowOverridePanacheRepository
import com.openbank.treasury.infrastructure.persistence.repository.CashFlowPanacheRepository
import com.openbank.treasury.infrastructure.persistence.repository.DealCommandPanacheRepository
import com.openbank.treasury.infrastructure.persistence.repository.DealVersionPanacheRepository
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

@ApplicationScoped
class DealLookupPanacheRepository : PanacheRepository<DealEntity>

/** The stored cash-flow ledger (ADR-0344 D4): rows, overrides, conflicts and deal versions. */
@ApplicationScoped
@Suppress("TooManyFunctions")
class CashFlowRepositoryImpl(
    private val flowRepo: CashFlowPanacheRepository,
    private val overrideRepo: CashFlowOverridePanacheRepository,
    private val conflictRepo: CashFlowConflictPanacheRepository,
    private val versionRepo: DealVersionPanacheRepository,
    private val commands: DealCommandPanacheRepository,
    private val deals: DealLookupPanacheRepository,
    private val outbox: TreasuryOutboxRepository,
    private val clock: Clock,
) : CashFlowRepository {

    override suspend fun flows(dealId: UUID, scheduleVersion: Int?): List<CashFlow> = Panache.withSession {
        val version: Uni<Int?> = if (scheduleVersion != null) {
            Uni.createFrom().item(scheduleVersion)
        } else {
            flowRepo.find("dealId = ?1 order by scheduleVersion desc", dealId).firstResult().map { it?.scheduleVersion }
        }
        version.flatMap { v ->
            if (v == null) {
                Uni.createFrom().item(emptyList())
            } else {
                flowRepo.find(
                    "dealId = ?1 and scheduleVersion = ?2 order by paymentDate asc, legIndex asc, periodIndex asc, kind asc",
                    dealId,
                    v,
                ).list()
            }
        }
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun overrides(dealId: UUID): List<CashFlowOverride> = Panache.withSession {
        overrideRepo.find("dealId = ?1 order by proposedAt asc", dealId).list()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun overridesByStatus(status: OverrideStatus, limit: Int): List<CashFlowOverride> =
        Panache.withSession {
            overrideRepo.find("status = ?1 order by proposedAt asc", status.name).page(0, limit).list()
        }.awaitSuspending().map { it.toDomain() }

    override suspend fun findOverride(overrideId: UUID): CashFlowOverride? = Panache.withSession {
        overrideRepo.find("overrideId", overrideId).firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun conflicts(dealId: UUID): List<CashFlowConflict> = Panache.withSession {
        conflictRepo.find("dealId = ?1 order by detectedAt asc", dealId).list()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun versions(dealId: UUID): List<DealVersionView> = Panache.withSession {
        versionRepo.find("dealId = ?1 order by version asc", dealId).list()
    }.awaitSuspending().map {
        DealVersionView(
            dealId = it.dealId,
            version = it.version,
            scheduleVersion = it.scheduleVersion,
            scheduleHash = it.scheduleHash,
            createdBy = it.createdBy,
            createdAt = it.createdAt,
            changes = it.changes?.split('\n')?.filter(String::isNotBlank).orEmpty(),
        )
    }

    override suspend fun saveOverride(
        override: CashFlowOverride,
        priorStatus: OverrideStatus?,
        flow: CashFlow?,
        event: DealEvent?,
        command: CommandKey?,
    ) {
        Panache.withTransaction {
            overrideRepo.find("overrideId", override.overrideId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
                .flatMap { row ->
                    if (priorStatus == null) {
                        check(row == null) { "override ${override.overrideId} already exists" }
                        overrideRepo.persist(CashFlowOverrideEntity().fill(override)).replaceWithVoid()
                    } else {
                        checkNotNull(row) { "override ${override.overrideId} disappeared" }
                        check(row.status == priorStatus.name) {
                            "override ${override.overrideId} is now ${row.status}, not $priorStatus; reload and retry"
                        }
                        row.fill(override)
                        Uni.createFrom().voidItem()
                    }
                }
                .flatMap { flow?.let { applyFlow(it) } ?: Uni.createFrom().voidItem() }
                .flatMap {
                    command?.let {
                        commands.persist(
                            DealCommandEntity().apply {
                                idempotencyKey = it.key
                                action = it.action
                                dealId = it.dealId
                                createdAt = clock.instant()
                            },
                        ).replaceWithVoid()
                    } ?: Uni.createFrom().voidItem()
                }
                .flatMap {
                    event?.let {
                        outbox.persistInTransaction(
                            OutboxMessage(
                                aggregateId = override.dealId,
                                eventType = it.eventType,
                                payload = it.payload,
                                createdAt = clock.instant(),
                            ),
                        )
                    } ?: Uni.createFrom().voidItem()
                }
        }.awaitSuspending()
    }

    /** The flow row is locked too: an override applies only to the flow as it stands. */
    private fun applyFlow(flow: CashFlow): Uni<Void> =
        flowRepo.find("flowId", flow.flowId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult().map { row ->
            checkNotNull(row) { "flow ${flow.flowId} disappeared" }
            row.fill(flow)
        }.replaceWithVoid()

    override suspend fun calendarFlows(from: LocalDate, to: LocalDate, bookId: String?): List<CalendarFlow> {
        val dealRows = Panache.withSession {
            if (bookId == null) {
                deals.find("kind in ?1 and scheduleVersion > 0", LIVE_KINDS).list()
            } else {
                deals.find("kind in ?1 and scheduleVersion > 0 and bookId = ?2", LIVE_KINDS, bookId).list()
            }
        }.awaitSuspending().filter { it.state !in DEAD_STATES }
        if (dealRows.isEmpty()) return emptyList()
        val byDeal = dealRows.associateBy { it.dealId }
        val rows = Panache.withSession {
            flowRepo.find(
                "dealId in ?1 and ((paymentDate >= ?2 and paymentDate <= ?3) or (fixingDate >= ?2 and fixingDate <= ?3))",
                byDeal.keys.toList(),
                from,
                to,
            ).list()
        }.awaitSuspending()
        return rows.filter { it.scheduleVersion == byDeal.getValue(it.dealId).scheduleVersion }.map {
            val d = byDeal.getValue(it.dealId)
            CalendarFlow(it.toDomain(), d.bookId, d.maturityDate, d.product)
        }
    }

    private companion object {
        val LIVE_KINDS = listOf(DealKind.ACTUAL.name, DealKind.PLANNED.name)
        val DEAD_STATES = setOf("CANCELLED", "REVERSED", "DRAFT")
    }
}
