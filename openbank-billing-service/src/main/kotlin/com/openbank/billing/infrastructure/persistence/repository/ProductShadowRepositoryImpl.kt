// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.persistence.repository

import com.openbank.billing.application.port.out.ProductShadowQueuePort
import com.openbank.billing.application.port.out.QueuedShadowInput
import com.openbank.billing.application.port.out.RevisionIrCopyPort
import com.openbank.billing.application.port.out.StoredRevisionIr
import com.openbank.billing.domain.ProductShadowInput
import com.openbank.billing.domain.ShadowLegacyFee
import com.openbank.billing.infrastructure.persistence.entity.ProductRevisionIrEntity
import com.openbank.billing.infrastructure.persistence.entity.ProductShadowInputEntity
import com.openbank.libs.audit.CanonicalJson
import com.openbank.libs.product.ir.IrJson
import com.openbank.libs.productrevision.IrSignature
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.PersistenceException
import org.hibernate.LockMode
import org.hibernate.reactive.mutiny.Mutiny
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Row mapping for the shadow queue; shared with [BillingAssessmentRepositoryImpl], which writes the row. */
internal object ProductShadowRows {
    fun entity(assessmentId: UUID, input: ProductShadowInput, now: Instant) = ProductShadowInputEntity().apply {
        this.assessmentId = assessmentId
        cycleId = input.cycleId
        accountId = input.accountId
        currency = input.currency
        productRevisionId = UUID.fromString(input.productRevisionId)
        balance = input.balance
        monthlyTurnover = input.monthlyTurnover
        aggregatePocketBalance = input.aggregatePocketBalance
        segment = input.segment
        legacyFees = CanonicalJson.write(
            input.legacyFees.map {
                mapOf("feeId" to it.feeId, "chargedAmount" to it.chargedAmount.toPlainString(), "waived" to it.waived)
            },
        )
        status = STATUS_PENDING
        attempts = 0
        nextAttemptAt = now
        createdAt = now
    }

    fun domain(e: ProductShadowInputEntity) = ProductShadowInput(
        cycleId = e.cycleId,
        accountId = e.accountId,
        currency = e.currency,
        productRevisionId = e.productRevisionId.toString(),
        balance = e.balance,
        monthlyTurnover = e.monthlyTurnover,
        aggregatePocketBalance = e.aggregatePocketBalance,
        segment = e.segment,
        legacyFees = (IrJson.parse(e.legacyFees) as List<*>).map { item ->
            val fee = item as Map<*, *>
            ShadowLegacyFee(
                feeId = fee["feeId"] as String,
                chargedAmount = BigDecimal(fee["chargedAmount"] as String),
                waived = fee["waived"] as Boolean,
            )
        },
    )

    const val STATUS_PENDING = "PENDING"
    const val STATUS_CLAIMED = "CLAIMED"
    const val STATUS_DONE = "DONE"
}

/**
 * The shadow queue over `billing_product_shadow_input` (V10). [claim] locks due rows with
 * `SKIP LOCKED`, so two billing pods never compare one decision twice; a claim older than [lease]
 * is offered again (its pod died mid-batch). Retries back off linearly by [backoff] per attempt.
 */
@ApplicationScoped
class ProductShadowQueueRepositoryImpl(private val sf: Mutiny.SessionFactory, private val clock: Clock) :
    ProductShadowQueuePort {

    private val lease: Duration = Duration.ofMinutes(LEASE_MINUTES)
    private val backoff: Duration = Duration.ofMinutes(BACKOFF_MINUTES)

    override suspend fun claim(limit: Int): List<QueuedShadowInput> {
        val now = clock.instant()
        return sf.withTransaction { s ->
            s.createQuery(
                "FROM ProductShadowInputEntity WHERE (status = :pending AND nextAttemptAt <= :now) " +
                    "OR (status = :claimed AND claimedAt < :stale) ORDER BY createdAt",
                ProductShadowInputEntity::class.java,
            ).setParameter("pending", ProductShadowRows.STATUS_PENDING)
                .setParameter("claimed", ProductShadowRows.STATUS_CLAIMED)
                .setParameter("now", now)
                .setParameter("stale", now.minus(lease))
                .setMaxResults(limit)
                .setLockMode(LockMode.UPGRADE_SKIPLOCKED)
                .resultList
                .map { rows ->
                    rows.map { row ->
                        row.status = ProductShadowRows.STATUS_CLAIMED
                        row.claimedAt = now
                        QueuedShadowInput(row.id, ProductShadowRows.domain(row), row.attempts)
                    }
                }
        }.awaitSuspending()
    }

    /**
     * Data minimisation: the frozen account facts (balance, turnover, pocket balance, segment) exist only
     * so the comparator can re-decide; once it has, they are erased and only the outcome remains. The
     * legacy decision itself is already held in `assessed_fee`.
     */
    override suspend fun complete(id: UUID, outcome: String) = update(id) { row ->
        row.status = ProductShadowRows.STATUS_DONE
        row.outcome = outcome
        row.completedAt = clock.instant()
        row.balance = null
        row.monthlyTurnover = null
        row.aggregatePocketBalance = null
        row.segment = null
    }

    override suspend fun retryLater(id: UUID, outcome: String) = update(id) { row ->
        row.status = ProductShadowRows.STATUS_PENDING
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
    }
}

/** Billing's immutable local IR copies (`billing_product_revision_ir`, V10). Insert-if-absent, never updated. */
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
