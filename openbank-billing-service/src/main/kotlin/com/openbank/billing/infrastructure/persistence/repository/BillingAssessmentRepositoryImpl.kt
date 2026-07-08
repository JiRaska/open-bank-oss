// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.persistence.repository

import com.openbank.billing.application.port.out.BillingAssessmentRepository
import com.openbank.billing.domain.AssessedFee
import com.openbank.billing.domain.BillingAssessment
import com.openbank.billing.domain.PostingStatus
import com.openbank.billing.infrastructure.persistence.entity.AssessedFeeEntity
import com.openbank.billing.infrastructure.persistence.entity.BillingCycleAssessmentEntity
import com.openbank.billing.infrastructure.persistence.entity.BillingOutboxEntity
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.libs.product.WaiveReason
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Reactive persistence for [BillingAssessment] (ADR-0143 phase 2c). The critical invariant this
 * class owns: [persistWithPostingIntent] writes the assessment, every [AssessedFee] row, and the
 * outbox row for every chargeable fee **inside one `sf.withTransaction` block** — assessment and
 * the intent-to-post commit atomically, so a crash between them is impossible (either both are
 * durable or neither is), matching ADR-0143 step 2 exactly.
 */
@ApplicationScoped
class BillingAssessmentRepositoryImpl(private val sf: Mutiny.SessionFactory, private val clock: Clock) :
    BillingAssessmentRepository {

    override suspend fun findExisting(cycleId: String, accountId: String, currency: String): BillingAssessment? {
        val assessment = sf.withSession { s ->
            s.createQuery(
                "FROM BillingCycleAssessmentEntity WHERE cycleId = :c AND accountId = :a AND currency = :cur",
                BillingCycleAssessmentEntity::class.java,
            ).setParameter("c", cycleId).setParameter("a", accountId).setParameter("cur", currency)
                .setMaxResults(1).singleResultOrNull
        }.awaitSuspending() ?: return null

        val fees = sf.withSession { s ->
            s.createQuery(
                "FROM AssessedFeeEntity WHERE assessmentId = :id ORDER BY feeId",
                AssessedFeeEntity::class.java,
            ).setParameter("id", assessment.id).resultList
        }.awaitSuspending()

        return assessment.toDomain(fees)
    }

    override suspend fun persistWithPostingIntent(assessment: BillingAssessment): BillingAssessment {
        val now = Instant.now(clock)
        val assessmentEntity = BillingCycleAssessmentEntity().apply {
            cycleId = assessment.cycleId
            accountId = assessment.accountId
            currency = assessment.currency
            skipped = assessment.skipped
            skipReason = assessment.skipReason
            createdAt = now
            updatedAt = now
        }

        val chargeableIdempotencyKeys = assessment.journalCommands().map { it.idempotencyKey }.toSet()

        val feeEntities = assessment.assessedFees.map { fee ->
            val chargeable = fee.idempotencyKey in chargeableIdempotencyKeys
            AssessedFeeEntity().apply {
                assessmentId = assessmentEntity.id
                cycleId = fee.cycleId
                accountId = fee.accountId
                feeId = fee.feeId
                feeName = fee.name
                currency = fee.currency
                chargedAmount = fee.chargedAmount
                waived = fee.waived
                waiveReason = fee.reason.name
                idempotencyKey = fee.idempotencyKey
                postingStatus = if (chargeable) PostingStatus.PENDING else PostingStatus.NOT_APPLICABLE
                createdAt = now
                updatedAt = now
            }
        }

        val outboxEntities = feeEntities.filter { it.postingStatus == PostingStatus.PENDING }.map { fee ->
            BillingOutboxEntity().apply {
                eventId = UUID.randomUUID()
                aggregateId = fee.id
                eventType = "billing.fee.post-intent.v1"
                payload = feePostIntentPayload(fee)
                status = OutboxStatus.PENDING.name
                attemptCount = 0
                createdAt = now
                updatedAt = now
            }
        }

        sf.withTransaction { s, _ ->
            s.persist(assessmentEntity)
                .chain { _ -> s.persistAll(*feeEntities.toTypedArray()) }
                .chain { _ -> s.persistAll(*outboxEntities.toTypedArray()) }
        }.awaitSuspending()

        return assessmentEntity.toDomain(feeEntities)
    }

    override suspend fun markPosted(idempotencyKey: String, journalId: UUID) {
        val now = Instant.now(clock)
        sf.withTransaction { s, _ ->
            s.createMutationQuery(
                "UPDATE AssessedFeeEntity SET postingStatus = :st, journalId = :j, postedAt = :p, updatedAt = :u " +
                    "WHERE idempotencyKey = :k",
            ).setParameter("st", PostingStatus.POSTED)
                .setParameter("j", journalId)
                .setParameter("p", now)
                .setParameter("u", now)
                .setParameter("k", idempotencyKey)
                .executeUpdate()
        }.awaitSuspending()
    }

    override suspend fun markFailed(idempotencyKey: String) {
        val now = Instant.now(clock)
        sf.withTransaction { s, _ ->
            s.createMutationQuery(
                "UPDATE AssessedFeeEntity SET postingStatus = :st, updatedAt = :u WHERE idempotencyKey = :k",
            ).setParameter("st", PostingStatus.FAILED)
                .setParameter("u", now)
                .setParameter("k", idempotencyKey)
                .executeUpdate()
        }.awaitSuspending()
    }

    private fun feePostIntentPayload(fee: AssessedFeeEntity): String =
        "{\"schemaVersion\":1," +
            "\"idempotencyKey\":\"${fee.idempotencyKey}\",\"cycleId\":\"${fee.cycleId}\"," +
            "\"accountId\":\"${fee.accountId}\",\"feeId\":\"${fee.feeId}\"," +
            "\"amount\":\"${fee.chargedAmount}\",\"currency\":\"${fee.currency}\"," +
            "\"description\":\"Fee charge: ${fee.feeName}\"}"
}

private fun BillingCycleAssessmentEntity.toDomain(fees: List<AssessedFeeEntity>): BillingAssessment =
    BillingAssessment(
        cycleId = cycleId,
        accountId = accountId,
        currency = currency,
        skipped = skipped,
        skipReason = skipReason,
        assessedFees = fees.map { it.toDomain() },
    )

private fun AssessedFeeEntity.toDomain(): AssessedFee = AssessedFee(
    cycleId = cycleId,
    accountId = accountId,
    feeId = feeId,
    name = feeName,
    currency = currency,
    chargedAmount = chargedAmount,
    waived = waived,
    reason = WaiveReason.valueOf(waiveReason),
    postingStatus = postingStatus,
    journalId = journalId,
)
