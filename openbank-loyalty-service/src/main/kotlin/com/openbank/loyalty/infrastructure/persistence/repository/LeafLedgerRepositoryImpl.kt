// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.infrastructure.persistence.repository

import com.openbank.loyalty.application.port.out.LeafLedgerRepository
import com.openbank.loyalty.domain.BenefitGrant
import com.openbank.loyalty.domain.LeafEarnSource
import com.openbank.loyalty.domain.LeafEntryType
import com.openbank.loyalty.domain.LeafLedger
import com.openbank.loyalty.domain.LeafLedgerEntry
import com.openbank.loyalty.domain.Leaves
import com.openbank.loyalty.infrastructure.outbox.LeafOutboxMessages
import com.openbank.loyalty.infrastructure.persistence.entity.BenefitGrantEntity
import com.openbank.loyalty.infrastructure.persistence.entity.LeafLedgerEntryEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The ledger's Postgres adapter. Every mutating method writes its domain rows AND the matching
 * outbox rows inside ONE `Panache.withTransaction` — the property `LoyaltyLedgerOutboxIT` proves
 * against a real database. A mocked repository cannot observe a transaction, so a unit test can
 * never establish this; that is why the integration test exists rather than a second mock.
 *
 * Note the id strategy: [LeafLedgerEntryEntity] and [BenefitGrantEntity] carry an
 * application-assigned `@Id`, so `persist` is INSERT-only here by design — nothing in this
 * adapter updates an entry through `persist`. The one in-place update (a lot's remaining balance)
 * goes through an explicit `update` query, never through a re-persist, which on an
 * application-assigned id would fail with a duplicate-key violation at flush.
 */
@ApplicationScoped
class LeafLedgerRepositoryImpl(
    private val outbox: LoyaltyOutboxRepositoryImpl,
    private val grants: BenefitGrantRepositoryImpl,
    private val messages: LeafOutboxMessages,
) : LeafLedgerRepository,
    PanacheRepositoryBase<LeafLedgerEntryEntity, UUID> {

    override suspend fun entriesFor(partyId: UUID): List<LeafLedgerEntry> = Panache.withSession {
        find("partyId = ?1 order by occurredAt asc", partyId).list()
    }.map { rows -> rows.map { it.toDomain() } }.awaitSuspending()

    override suspend fun findEarn(partyId: UUID, source: LeafEarnSource, correlationEventId: UUID): LeafLedgerEntry? =
        Panache.withSession {
            find(
                "partyId = ?1 and entryType = ?2 and earnSourceId = ?3 and correlationEventId = ?4",
                partyId,
                LeafEntryType.EARN.name,
                source.id,
                correlationEventId,
            ).firstResult()
        }.map { it?.toDomain() }.awaitSuspending()

    override suspend fun earnedInYearOf(partyId: UUID, at: Instant): Leaves {
        val yearStart = at.atZone(ZoneOffset.UTC).withDayOfYear(1).toLocalDate()
            .atStartOfDay(ZoneOffset.UTC).toInstant()
        val yearEnd = yearStart.atZone(ZoneOffset.UTC).plusYears(1).toInstant()
        val total = Panache.withSession {
            find(
                "partyId = ?1 and entryType = ?2 and occurredAt >= ?3 and occurredAt < ?4",
                partyId,
                LeafEntryType.EARN.name,
                yearStart,
                yearEnd,
            ).list()
        }.map { rows -> rows.sumOf { it.leaves } }.awaitSuspending()
        return Leaves.of(total)
    }

    override suspend fun appendEarn(entry: LeafLedgerEntry) {
        Panache.withTransaction {
            persist(LeafLedgerEntryEntity.from(entry)).chain { _ ->
                outbox.persistInTransaction(messages.earned(entry))
            }
        }.awaitSuspending()
    }

    override suspend fun appendBurnAndGrant(
        entry: LeafLedgerEntry,
        debits: List<LeafLedger.LotDebit>,
        grant: BenefitGrant,
    ) {
        Panache.withTransaction {
            debits.fold(Uni.createFrom().voidItem()) { acc, debit ->
                acc.chain { _ -> debitLot(debit) }
            }.chain { _ ->
                persist(LeafLedgerEntryEntity.from(entry))
            }.chain { _ ->
                grants.persistInTransaction(grant)
            }.chain { _ ->
                outbox.persistInTransaction(messages.benefitGranted(grant, entry))
            }
        }.awaitSuspending()
    }

    override suspend fun appendExpiries(entries: List<LeafLedgerEntry>, lotIds: List<UUID>) {
        if (entries.isEmpty()) return
        Panache.withTransaction {
            lotIds.fold(Uni.createFrom().voidItem()) { acc, lotId ->
                acc.chain { _ -> update("remainingLeaves = 0 where id = ?1", lotId).replaceWithVoid() }
            }.chain { _ ->
                entries.fold(Uni.createFrom().voidItem()) { acc, e ->
                    acc.chain { _ ->
                        persist(LeafLedgerEntryEntity.from(e))
                            .chain { _ -> outbox.persistInTransaction(messages.expired(e)) }
                            .replaceWithVoid()
                    }
                }
            }
        }.awaitSuspending()
    }

    override suspend fun partiesWithExpirableLots(at: Instant, limit: Int): List<UUID> = Panache.withSession {
        find(
            "entryType = ?1 and remainingLeaves > 0 and expiresAt <= ?2",
            LeafEntryType.EARN.name,
            at,
        ).range(0, limit.coerceAtLeast(1) - 1).list()
    }.map { rows -> rows.map { it.partyId }.distinct() }.awaitSuspending()

    override suspend fun outstandingLeaves(at: Instant): Long = Panache.withSession {
        find(
            "entryType = ?1 and remainingLeaves > 0 and (expiresAt is null or expiresAt > ?2)",
            LeafEntryType.EARN.name,
            at,
        ).list()
    }.map { rows -> rows.sumOf { it.remainingLeaves.toLong() } }.awaitSuspending()

    private fun debitLot(debit: LeafLedger.LotDebit): Uni<Void> =
        update("remainingLeaves = remainingLeaves - ?1 where id = ?2", debit.amount.value, debit.lotId)
            .replaceWithVoid()
}
