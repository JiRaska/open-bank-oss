// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.persistence.repository

import com.openbank.billing.application.port.out.BillingAssessmentRepository
import com.openbank.billing.domain.AnnualFeeSummary
import com.openbank.billing.domain.AssessedFee
import com.openbank.billing.domain.BillingAssessment
import com.openbank.billing.domain.PostingStatus
import com.openbank.billing.infrastructure.outbox.AnnualFeeSummaryOutboxPayloads
import com.openbank.billing.infrastructure.outbox.AssessedFeeOutboxPayloads
import com.openbank.billing.infrastructure.persistence.entity.AssessedFeeEntity
import com.openbank.billing.infrastructure.persistence.entity.BillingCycleAssessmentEntity
import com.openbank.billing.infrastructure.persistence.entity.BillingOutboxEntity
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.libs.product.WaiveReason
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.pgclient.PgException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.PersistenceException
import org.hibernate.reactive.mutiny.Mutiny
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Reactive persistence for [BillingAssessment] (ADR-0143 phase 2c). The critical invariant this
 * class owns: [persistWithPostingIntent] writes the assessment, every [AssessedFee] row, and the
 * outbox row for every chargeable fee **inside one `sf.withTransaction` block** — assessment and
 * the intent-to-post commit atomically, so a crash between them is impossible (either both are
 * durable or neither is), matching ADR-0143 step 2 exactly.
 *
 * [TooManyFunctions] suppressed: one Panache repo implementing the full billing-assessment
 * lifecycle (assess/post/reverse) plus the ADR-0248 annual-summary outbox append; splitting it
 * would separate operations that share the same `sf.withTransaction` atomicity invariant.
 */
@ApplicationScoped
@Suppress("TooManyFunctions")
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
                eventId = Ids.newId()
                synthetic = false
                aggregateId = fee.id
                eventType = "billing.fee.post-intent.v1"
                payload = AssessedFeeOutboxPayloads.postIntent(fee)
                status = OutboxStatus.PENDING.name
                attemptCount = 0
                createdAt = now
                updatedAt = now
            }
        }

        // Individual chained s.persist() calls, not persistAll(vararg) — avoids a spread
        // operator over a to-typed-array (detekt SpreadOperator) and mirrors this repo's own
        // multi-entity-one-transaction convention (see ComplaintRepositoryImpl.save:
        // s.persist(entity).flatMap { s.persist(outbox.toEntity()) }).
        val allEntities: List<Any> = listOf(assessmentEntity) + feeEntities + outboxEntities
        try {
            sf.withTransaction { s, _ ->
                allEntities.fold(Uni.createFrom().voidItem() as Uni<*>) { acc, entity ->
                    acc.chain { _ -> s.persist(entity) }
                }
            }.awaitSuspending()
        } catch (e: PersistenceException) {
            return recoverConcurrentReplay(e, assessment)
        } catch (e: PgException) {
            return recoverConcurrentReplay(e, assessment)
        }

        return assessmentEntity.toDomain(feeEntities)
    }

    /**
     * TOCTOU recovery (fix-review finding): [BillingCycleService.assessAndPost] calls
     * [findExisting] then [persistWithPostingIntent] as two separate operations, so two
     * concurrent calls for the same `(cycleId, accountId, currency)` can both observe "no
     * existing assessment" and both attempt this insert. The DB's
     * `uq_billing_cycle_assessment` unique constraint lets exactly one of them win; the loser
     * recovers into the same idempotent-replay contract as a sequential re-run (mirrors
     * `AccountService.recoverConcurrentReplay` for `account_idempotency`) instead of surfacing an
     * unhandled 500. A conflict on any OTHER constraint is a real bug and must not be swallowed.
     */
    private suspend fun recoverConcurrentReplay(e: RuntimeException, assessment: BillingAssessment): BillingAssessment {
        val isAssessmentConflict = generateSequence<Throwable>(e) { it.cause.takeIf { c -> c !== it } }
            .any { it.message?.contains("uq_billing_cycle_assessment", ignoreCase = true) == true }
        if (!isAssessmentConflict) throw e
        return findExisting(assessment.cycleId, assessment.accountId, assessment.currency) ?: throw e
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

    override suspend fun findFeeByIdempotencyKey(idempotencyKey: String): AssessedFee? = sf.withSession { s ->
        s.createQuery(
            "FROM AssessedFeeEntity WHERE idempotencyKey = :k",
            AssessedFeeEntity::class.java,
        ).setParameter("k", idempotencyKey).setMaxResults(1).singleResultOrNull
    }.awaitSuspending()?.toDomain()

    /**
     * Atomically (ADR-0143 phase 2e, mirrors [persistWithPostingIntent]'s charge-leg atomicity):
     * flip the fee POSTED -> REVERSAL_PENDING and append its compensating-journal outbox row in
     * the SAME transaction. Guards fail cleanly rather than throwing a generic DB error:
     *  - no fee with this key -> null (caller reports "never assessed").
     *  - not POSTED (e.g. still PENDING, FAILED, or NOT_APPLICABLE) -> throws
     *    [IllegalStateException] (caller reports "nothing to reverse" — a fee that was never
     *    posted has no ledger entry to compensate).
     *  - already REVERSAL_PENDING/REVERSED -> returns the fee UNCHANGED, no new outbox row (the
     *    idempotent-replay contract for reversal, same shape as the charge leg's replay).
     */
    override suspend fun persistReversalIntent(idempotencyKey: String, reason: String): AssessedFee? {
        val now = Instant.now(clock)
        val updated: AssessedFeeEntity? = sf.withTransaction<AssessedFeeEntity?> { s, _ ->
            s.createQuery(
                "FROM AssessedFeeEntity WHERE idempotencyKey = :k",
                AssessedFeeEntity::class.java,
            ).setParameter("k", idempotencyKey).setMaxResults(1).singleResultOrNull.chain { fee ->
                if (fee == null) {
                    return@chain Uni.createFrom().nullItem()
                }
                if (fee.postingStatus == PostingStatus.REVERSAL_PENDING ||
                    fee.postingStatus == PostingStatus.REVERSED
                ) {
                    return@chain Uni.createFrom().item(fee)
                }
                if (fee.postingStatus != PostingStatus.POSTED) {
                    return@chain Uni.createFrom().failure<AssessedFeeEntity>(
                        IllegalStateException(
                            "fee idempotencyKey=$idempotencyKey is ${fee.postingStatus}, not POSTED — nothing to reverse",
                        ),
                    )
                }
                fee.postingStatus = PostingStatus.REVERSAL_PENDING
                fee.reversalReason = reason
                fee.updatedAt = now
                val outboxEntity = BillingOutboxEntity().apply {
                    eventId = Ids.newId()
                    synthetic = false
                    aggregateId = fee.id
                    eventType = "billing.fee.reversal-intent.v1"
                    payload = AssessedFeeOutboxPayloads.reversalIntent(fee, reason)
                    status = OutboxStatus.PENDING.name
                    attemptCount = 0
                    createdAt = now
                    updatedAt = now
                }
                s.persist(outboxEntity).replaceWith(fee)
            }
        }.awaitSuspending()
        return updated?.toDomain()
    }

    override suspend fun markReversed(idempotencyKey: String, reversalJournalId: UUID) {
        val now = Instant.now(clock)
        sf.withTransaction { s, _ ->
            s.createMutationQuery(
                "UPDATE AssessedFeeEntity SET postingStatus = :st, reversalJournalId = :j, reversedAt = :p, " +
                    "updatedAt = :u WHERE idempotencyKey = :k",
            ).setParameter("st", PostingStatus.REVERSED)
                .setParameter("j", reversalJournalId)
                .setParameter("p", now)
                .setParameter("u", now)
                .setParameter("k", idempotencyKey)
                .executeUpdate()
        }.awaitSuspending()
    }

    override suspend fun markReversalFailed(idempotencyKey: String) {
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

    /**
     * ADR-0248 annual fee-summary read. `postingStatus = :st` (POSTED only) is the whole filter —
     * see [BillingAssessmentRepository.postedFeesForAccount]'s KDoc for why PENDING/FAILED/
     * REVERSAL_PENDING/REVERSED/NOT_APPLICABLE rows must not count.
     */
    override suspend fun postedFeesForAccount(accountId: String, from: Instant, to: Instant): List<AssessedFee> =
        sf.withSession { s ->
            s.createQuery(
                "FROM AssessedFeeEntity WHERE accountId = :a AND postingStatus = :st " +
                    "AND postedAt >= :from AND postedAt < :to ORDER BY feeId",
                AssessedFeeEntity::class.java,
            ).setParameter("a", accountId)
                .setParameter("st", PostingStatus.POSTED)
                .setParameter("from", from)
                .setParameter("to", to)
                .resultList
        }.awaitSuspending().map { it.toDomain() }

    /**
     * ADR-0248 annual fee-summary trigger. [aggregateIdFor] is deterministic on
     * `(accountId, year)`, so re-running the annual scheduler for an account/year that already
     * has a row is a genuine no-op — the existence check and the insert happen in the SAME
     * transaction, closing the same check-then-act race [persistWithPostingIntent] documents for
     * the charge leg (two concurrent scheduler runs can only ever insert one row per account/year;
     * the `billing_outbox` primary key has no unique constraint on `aggregate_id` to backstop this
     * the way `uq_billing_cycle_assessment` backstops the charge leg, so the transactional
     * read-then-write here IS the whole guarantee — acceptable because, unlike the charge leg,
     * this table has exactly one writer: the annual scheduler, never a customer-facing request).
     */
    override suspend fun appendAnnualFeeSummaryEvent(summary: AnnualFeeSummary, occurredAt: Instant): Boolean {
        val aggregateId = aggregateIdFor(summary.accountId, summary.year)
        val now = Instant.now(clock)
        val payload = AnnualFeeSummaryOutboxPayloads.toJson(summary, occurredAt)
        return sf.withTransaction { s, _ ->
            s.createQuery(
                "FROM BillingOutboxEntity WHERE aggregateId = :id AND eventType = :et",
                BillingOutboxEntity::class.java,
            ).setParameter("id", aggregateId)
                .setParameter("et", ANNUAL_FEE_SUMMARY_EVENT_TYPE)
                .setMaxResults(1)
                .singleResultOrNull
                .chain { existing ->
                    if (existing != null) {
                        Uni.createFrom().item(false)
                    } else {
                        val entity = BillingOutboxEntity().apply {
                            eventId = Ids.newId()
                            synthetic = false
                            this.aggregateId = aggregateId
                            eventType = ANNUAL_FEE_SUMMARY_EVENT_TYPE
                            this.payload = payload
                            status = OutboxStatus.PENDING.name
                            attemptCount = 0
                            createdAt = now
                            updatedAt = now
                        }
                        s.persist(entity).replaceWith(true)
                    }
                }
        }.awaitSuspending()
    }

    companion object {
        /** Mirrors `LedgerOutboxEventPublisher.ANNUAL_FEE_SUMMARY_EVENT_TYPE` (ADR-0248). */
        const val ANNUAL_FEE_SUMMARY_EVENT_TYPE = "billing.annual-fee-summary.ready"

        /** Deterministic on (accountId, year) so [appendAnnualFeeSummaryEvent] is naturally idempotent. */
        private fun aggregateIdFor(accountId: String, year: Int): UUID =
            UUID.nameUUIDFromBytes("annual-fee-summary:$accountId:$year".toByteArray(StandardCharsets.UTF_8))
    }
}

private fun BillingCycleAssessmentEntity.toDomain(fees: List<AssessedFeeEntity>): BillingAssessment = BillingAssessment(
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
    reversalJournalId = reversalJournalId,
    reversalReason = reversalReason,
    postedAt = postedAt,
)
