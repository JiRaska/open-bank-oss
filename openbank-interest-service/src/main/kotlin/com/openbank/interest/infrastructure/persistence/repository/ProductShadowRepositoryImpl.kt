// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.persistence.repository

import com.openbank.interest.application.port.out.ProductShadowQueuePort
import com.openbank.interest.application.port.out.QueuedShadowInput
import com.openbank.interest.application.port.out.RevisionIrCopyPort
import com.openbank.interest.application.port.out.ShadowCompletion
import com.openbank.interest.application.port.out.ShadowLegacySourcePort
import com.openbank.interest.application.port.out.StoredRevisionIr
import com.openbank.interest.domain.shadow.ShadowLegacyAccrual
import com.openbank.interest.domain.shadow.ShadowLegacyCapitalization
import com.openbank.interest.domain.shadow.ShadowLegacyDecision
import com.openbank.interest.domain.shadow.ShadowSourceKind
import com.openbank.interest.infrastructure.persistence.entity.AccountRateRevisionPinEntity
import com.openbank.interest.infrastructure.persistence.entity.CatalogInterestRateSnapshotEntity
import com.openbank.interest.infrastructure.persistence.entity.InterestAccrualEntity
import com.openbank.interest.infrastructure.persistence.entity.InterestCapitalizationEntity
import com.openbank.interest.infrastructure.persistence.entity.ProductRevisionIrEntity
import com.openbank.interest.infrastructure.persistence.entity.ProductShadowInputEntity
import com.openbank.libs.productrevision.IrSignature
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.PersistenceException
import org.hibernate.LockMode
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The shadow queue over `interest_product_shadow_input` (V21). [intake] enqueues committed legacy rows
 * idempotently (`ON CONFLICT DO NOTHING` on the source); [claim] locks due rows with `SKIP LOCKED`, so
 * two pods never compare one decision twice, and a claim older than the lease is offered again.
 */
@ApplicationScoped
class ProductShadowQueueRepositoryImpl(private val sf: Mutiny.SessionFactory, private val clock: Clock) :
    ProductShadowQueuePort {

    private val lease: Duration = Duration.ofMinutes(LEASE_MINUTES)
    private val backoff: Duration = Duration.ofMinutes(BACKOFF_MINUTES)

    override suspend fun intake(lookbackDays: Long, limit: Int): Int {
        val now = clock.instant()
        val since = OffsetDateTime.ofInstant(now.minus(Duration.ofDays(lookbackDays)), ZoneOffset.UTC)
        val nowTs = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
        var total = 0
        for ((kind, table) in SOURCES) {
            // Read only after the legacy transaction committed: the shadow never runs inside it (ADR-0352 §6).
            total += sf.withTransaction { s ->
                s.createNativeQuery<Int>(
                    "INSERT INTO interest_product_shadow_input " +
                        "(id, source_kind, source_id, status, attempts, next_attempt_at, created_at) " +
                        "SELECT gen_random_uuid(), :kind, src.id, 'PENDING', 0, :now, :now FROM $table src " +
                        "WHERE src.created_at >= :since AND NOT EXISTS (SELECT 1 FROM interest_product_shadow_input q " +
                        "WHERE q.source_kind = :kind AND q.source_id = src.id) " +
                        "ORDER BY src.created_at LIMIT :lim ON CONFLICT (source_kind, source_id) DO NOTHING",
                ).setParameter("kind", kind.name)
                    .setParameter("now", nowTs)
                    .setParameter("since", since)
                    .setParameter("lim", limit)
                    .executeUpdate()
            }.awaitSuspending()
        }
        return total
    }

    override suspend fun claim(limit: Int): List<QueuedShadowInput> {
        val now = clock.instant()
        return sf.withTransaction { s ->
            s.createQuery(
                "FROM ProductShadowInputEntity WHERE (status = :pending AND nextAttemptAt <= :now) " +
                    "OR (status = :claimed AND claimedAt < :stale) ORDER BY createdAt",
                ProductShadowInputEntity::class.java,
            ).setParameter("pending", STATUS_PENDING)
                .setParameter("claimed", STATUS_CLAIMED)
                .setParameter("now", now)
                .setParameter("stale", now.minus(lease))
                .setMaxResults(limit)
                .setLockMode(LockMode.UPGRADE_SKIPLOCKED)
                .resultList
                .map { rows ->
                    rows.map { row ->
                        row.status = STATUS_CLAIMED
                        row.claimedAt = now
                        QueuedShadowInput(row.id, ShadowSourceKind.valueOf(row.sourceKind), row.sourceId, row.attempts)
                    }
                }
        }.awaitSuspending()
    }

    override suspend fun complete(id: UUID, completion: ShadowCompletion) = update(id) { row ->
        row.status = STATUS_DONE
        row.outcome = completion.outcome
        row.ruleId = completion.ruleId
        row.legacyAmount = completion.legacyAmount
        row.irAmount = completion.irAmount
        row.completedAt = clock.instant()
    }

    override suspend fun retryLater(id: UUID, outcome: String) = update(id) { row ->
        row.status = STATUS_PENDING
        row.attempts += 1
        row.outcome = outcome
        row.claimedAt = null
        row.nextAttemptAt = clock.instant().plus(backoff.multipliedBy(row.attempts.toLong()))
    }

    private suspend fun update(id: UUID, change: (ProductShadowInputEntity) -> Unit) {
        sf.withTransaction { s ->
            s.find(ProductShadowInputEntity::class.java, id).map { row -> row?.let(change) }
        }.awaitSuspending()
    }

    companion object {
        private const val LEASE_MINUTES = 15L
        private const val BACKOFF_MINUTES = 10L
        const val STATUS_PENDING = "PENDING"
        const val STATUS_CLAIMED = "CLAIMED"
        const val STATUS_DONE = "DONE"
        private val SOURCES = listOf(
            ShadowSourceKind.ACCRUAL to "interest_accruals",
            ShadowSourceKind.CAPITALIZATION to "interest_capitalizations",
        )
    }
}

/**
 * Reads the committed legacy row back with its account's ADR-0341 pin. An accrual counts as decided
 * by the pin only when its `config_id` is the config the pinned revision's snapshot materialised; an
 * account-specific override wins over a pin in `InterestService.resolveRate`, and is not the revision's
 * decision.
 */
@ApplicationScoped
class ShadowLegacySourceRepositoryImpl(private val sf: Mutiny.SessionFactory) : ShadowLegacySourcePort {

    override suspend fun load(kind: ShadowSourceKind, sourceId: UUID): ShadowLegacyDecision? = when (kind) {
        ShadowSourceKind.ACCRUAL -> accrual(sourceId)
        ShadowSourceKind.CAPITALIZATION -> capitalization(sourceId)
    }

    private suspend fun accrual(id: UUID): ShadowLegacyDecision? {
        val a = sf.withSession { s -> s.find(InterestAccrualEntity::class.java, id) }.awaitSuspending() ?: return null
        val pin = pin(a.accountId, a.productId, a.currency)
        val pinnedConfig = pin?.let { revisionId ->
            sf.withSession { s -> s.find(CatalogInterestRateSnapshotEntity::class.java, revisionId) }
                .awaitSuspending()?.configId
        }
        return ShadowLegacyAccrual(
            sourceId = a.id,
            accountId = a.accountId,
            productId = a.productId,
            currency = a.currency,
            productRevisionId = pin?.toString(),
            accrualDate = a.accrualDate,
            balance = a.balance,
            accruedAmount = a.accruedAmount,
            decidedByPin = pinnedConfig != null && pinnedConfig == a.configId,
        )
    }

    private suspend fun capitalization(id: UUID): ShadowLegacyDecision? {
        val c = sf.withSession { s -> s.find(InterestCapitalizationEntity::class.java, id) }.awaitSuspending()
            ?: return null
        return ShadowLegacyCapitalization(
            sourceId = c.id,
            accountId = c.accountId,
            productId = c.productId,
            currency = c.currency,
            productRevisionId = pin(c.accountId, c.productId, c.currency)?.toString(),
            periodTo = c.periodTo,
            totalAccrued = c.totalAccrued,
            grossAmount = c.grossAmount,
            taxAmount = c.taxAmount,
        )
    }

    private suspend fun pin(accountId: UUID, productId: String, currency: String): UUID? = sf.withSession { s ->
        s.createQuery(
            "FROM AccountRateRevisionPinEntity WHERE accountId = :a AND productId = :p AND currency = :c",
            AccountRateRevisionPinEntity::class.java,
        ).setParameter("a", accountId).setParameter("p", productId).setParameter("c", currency)
            .setMaxResults(1)
            .singleResultOrNull
    }.awaitSuspending()?.revisionId
}

/** Interest's immutable local IR copies (`interest_product_revision_ir`, V21). Insert-if-absent, never updated. */
@ApplicationScoped
class RevisionIrCopyRepositoryImpl(private val sf: Mutiny.SessionFactory, private val clock: Clock) :
    RevisionIrCopyPort {

    override suspend fun find(revisionId: String): StoredRevisionIr? = sf.withSession { s ->
        s.find(ProductRevisionIrEntity::class.java, UUID.fromString(revisionId))
    }.awaitSuspending()?.toDomain()

    override suspend fun store(copy: StoredRevisionIr): StoredRevisionIr {
        val entity = ProductRevisionIrEntity().apply {
            revisionId = UUID.fromString(copy.revisionId)
            hasIr = copy.hasIr
            revisionHash = copy.revisionHash
            irJson = copy.irJson
            keyId = copy.signature?.keyId
            algorithm = copy.signature?.algorithm
            signature = copy.signature?.signature
            fetchedAt = clock.instant()
        }
        try {
            sf.withTransaction { s -> s.persist(entity) }.awaitSuspending()
        } catch (e: PersistenceException) {
            // Another comparator stored the same immutable revision first; its row is the one.
            return find(copy.revisionId) ?: throw e
        }
        return copy
    }

    private fun ProductRevisionIrEntity.toDomain() = StoredRevisionIr(
        revisionId = revisionId.toString(),
        hasIr = hasIr,
        revisionHash = revisionHash,
        irJson = irJson,
        signature = if (keyId != null && algorithm != null && signature != null) {
            IrSignature(keyId!!, algorithm!!, signature!!)
        } else {
            null
        },
    )
}
