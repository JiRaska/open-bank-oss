// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.outbox

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.billing.application.port.out.BillingAssessmentRepository
import com.openbank.billing.infrastructure.persistence.entity.BillingOutboxEntity
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxFailurePolicy
import com.openbank.libs.persistence.outbox.OutboxRepository
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * [OutboxRepository] for `billing_outbox` (ADR-0143 phase 2c/2e). Mirrors
 * `InterestOutboxRepositoryImpl` column-for-column; the one addition is [markFailed] also
 * flipping the originating [com.openbank.billing.domain.AssessedFee] to
 * [com.openbank.billing.domain.PostingStatus.FAILED] once the row goes terminal DEAD — so a
 * poison fee-posting (or, for a `billing.fee.reversal-intent.v1` row, a poison reversal) is
 * operator-visible on the fee itself, not only in the outbox table. Dispatch on `eventType`
 * (rather than assuming every row is a charge) since this table now carries two payload shapes.
 */
@ApplicationScoped
class BillingOutboxRepositoryImpl(private val assessments: BillingAssessmentRepository, private val clock: Clock) :
    OutboxRepository,
    PanacheRepository<BillingOutboxEntity> {

    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    override suspend fun listProcessable(limit: Int): List<OutboxEntry> = Panache.withSession {
        find(
            "status in (?1, ?2) order by createdAt asc",
            OutboxStatus.PENDING.name,
            OutboxStatus.FAILED.name,
        ).range(0, limit.coerceAtLeast(1) - 1).list()
    }.awaitSuspending().map { it.toEntry() }

    override suspend fun countProcessable(): Long = Panache.withSession {
        count("status in (?1, ?2)", OutboxStatus.PENDING.name, OutboxStatus.FAILED.name)
    }.awaitSuspending()

    /**
     * Count of terminal DEAD rows, for the `openbank.outbox.dead_lettered` gauge (#4701).
     *
     * Deliberately NOT part of [countProcessable]: DEAD is excluded from the backlog by design
     * (ADR-0050 N5 parks a poison row so it cannot starve the batch), which is exactly why a
     * fully-parked outbox reads `openbank_outbox_backlog{service="billing"} == 0` — the same
     * value a perfectly healthy service publishes. Measured 2026-08-15: two
     * `billing.fee.post-intent.v1` rows have sat DEAD since 2026-07-13 with the backlog gauge at
     * a healthy-looking zero the whole time.
     */
    suspend fun countDead(): Long = Panache.withSession {
        count("status = ?1", OutboxStatus.DEAD.name)
    }.awaitSuspending()

    /**
     * Reference implementation for the [OutboxRepository.claimProcessable] atomic-claim
     * override (#1201). One statement: the inner `SELECT ... FOR UPDATE SKIP LOCKED` locks and
     * skips-past whatever a concurrently running claim has already locked, and the outer
     * `UPDATE` flips exactly those rows to DISPATCHING and returns them — so two dispatcher
     * instances racing this at the same instant can never both claim the same row. Also reclaims
     * rows still DISPATCHING past [staleAfter] (a pod that claimed a row and then crashed or was
     * evicted before `markSent`/`markFailed`), so a claim can never strand a row forever.
     *
     * Plain native SQL rather than a Panache/HQL lock hint: `FOR UPDATE SKIP LOCKED` has no
     * `jakarta.persistence.LockModeType` equivalent, and the lock only has to be held for the
     * lifetime of this one statement/transaction — it does not need to (and must not) span the
     * network publish call that follows.
     */
    override suspend fun claimProcessable(limit: Int, staleAfter: Duration): List<OutboxEntry> {
        val now = Instant.now(clock)
        val staleThreshold = now.minus(staleAfter)
        return Panache.withTransaction {
            Panache.getSession().chain { session ->
                session.createNativeQuery(CLAIM_SQL, BillingOutboxEntity::class.java)
                    .setParameter("pending", OutboxStatus.PENDING.name)
                    .setParameter("failed", OutboxStatus.FAILED.name)
                    .setParameter("dispatching", OutboxStatus.DISPATCHING.name)
                    .setParameter("staleThreshold", staleThreshold)
                    .setParameter("claimLimit", limit.coerceAtLeast(1))
                    .setParameter("now", now)
                    .resultList
            }
        }.map { entities -> entities.map { it.toEntry() } }.awaitSuspending()
    }

    override suspend fun markSent(eventId: UUID, sentAt: Instant) {
        Panache.withTransaction {
            find("eventId", eventId).firstResult().invoke { e ->
                if (e != null) {
                    e.status = OutboxStatus.SENT.name
                    e.attemptCount += 1
                    e.sentAt = sentAt
                    e.lastError = null
                    e.updatedAt = sentAt
                }
            }.replaceWith(Unit)
        }.awaitSuspending()
    }

    override suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant): OutboxStatus {
        val outcome: Uni<FailureOutcome> = Panache.withTransaction {
            find("eventId", eventId).firstResult().map { e ->
                if (e == null) {
                    // Row not found -- unreachable in practice (the dispatcher only calls
                    // markFailed on a row it just claimed), but degrade gracefully rather than
                    // throw out of a batch that is otherwise mid-flight (#5128 finding 3).
                    FailureOutcome(OutboxStatus.FAILED, null)
                } else {
                    val status = applyFailure(e, error, failedAt)
                    val dead = if (status == OutboxStatus.DEAD) {
                        DeadRow(e.eventType, extractIdempotencyKey(e.eventType, e.payload))
                    } else {
                        null
                    }
                    FailureOutcome(status, dead)
                }
            }
        }
        // Outside the outbox row's own transaction (deliberately — a fee's posting_status is a
        // separate aggregate from the outbox row; both updates are individually durable, and a
        // crash between them just means the fee catches up to FAILED on a later markFailed retry
        // or is visible as "PENDING forever" — never silently POSTED).
        val result = outcome.awaitSuspending()
        val dead = result.dead ?: return result.status
        if (dead.eventType == ANNUAL_FEE_SUMMARY_EVENT_TYPE) {
            // ADR-0248: not a fee-posting event — there is no AssessedFee row to flip. A DEAD
            // annual-summary row is operator-visible via the `billing.outbox.dead` log line above
            // and the outbox backlog gauge; nothing on the fee side needs (or can) be updated.
            return result.status
        }
        val idempotencyKey = dead.idempotencyKey ?: return result.status
        if (dead.eventType == REVERSAL_INTENT_EVENT_TYPE) {
            assessments.markReversalFailed(idempotencyKey)
        } else {
            assessments.markFailed(idempotencyKey)
        }
        return result.status
    }

    /**
     * Proper Jackson deserialization (fix-review finding) rather than a regex against raw JSON —
     * [LedgerOutboxEventPublisher] already deserializes both the `billing.fee.post-intent.v1` and
     * `billing.fee.reversal-intent.v1` payload shapes via Jackson; reusing that approach here keeps
     * both readers equally robust to whitespace/field-order/escaping rather than depending on a
     * hand-rolled pattern. A reversal-intent payload carries BOTH its own `idempotencyKey` (the
     * reversal journal's key) AND `originalIdempotencyKey` (the fee being reversed) — dispatching
     * on `eventType` (rather than "whichever field parses first") is required so a DEAD reversal
     * row flags the ORIGINAL fee's `posting_status`, not a phantom row keyed by the reversal's own
     * (never-persisted-as-a-fee-row) idempotency key.
     */
    private fun extractIdempotencyKey(eventType: String, payload: String): String? = when (eventType) {
        // ADR-0248: no idempotencyKey field on this payload at all (it is not a fee) — skip the
        // parse attempt rather than let it fail-and-be-caught below.
        ANNUAL_FEE_SUMMARY_EVENT_TYPE -> null
        REVERSAL_INTENT_EVENT_TYPE ->
            runCatching {
                mapper.readValue(payload, OriginalIdempotencyKeyOnly::class.java).originalIdempotencyKey
            }.getOrNull()
        else -> runCatching { mapper.readValue(payload, IdempotencyKeyOnly::class.java).idempotencyKey }.getOrNull()
    }

    private data class DeadRow(val eventType: String, val idempotencyKey: String?)

    /** [markFailed]'s per-row result: the status the row was actually persisted with, plus the
     * fee-side follow-up data only populated when that status is terminal DEAD. */
    private data class FailureOutcome(val status: OutboxStatus, val dead: DeadRow?)

    /** Record a publish failure (ADR-0050 N5) — same policy every service's outbox repo applies. */
    private fun applyFailure(e: BillingOutboxEntity, error: String, at: Instant): OutboxStatus {
        e.attemptCount += 1
        e.lastError = error.take(OutboxFailurePolicy.MAX_ERROR_LEN)
        e.updatedAt = at
        val next = OutboxFailurePolicy.statusAfterFailure(e.attemptCount)
        e.status = next.name
        if (next == OutboxStatus.DEAD) {
            log.warnf(
                "billing.outbox.dead event_id=%s aggregate_id=%s event_type=%s attempts=%d last_error=%s",
                e.eventId,
                e.aggregateId,
                e.eventType,
                e.attemptCount,
                e.lastError,
            )
        }
        return next
    }

    companion object {
        private val log: Logger = Logger.getLogger(BillingOutboxRepositoryImpl::class.java)

        /** Mirrors `LedgerOutboxEventPublisher.REVERSAL_INTENT_EVENT_TYPE` (ADR-0143 phase 2e). */
        const val REVERSAL_INTENT_EVENT_TYPE = "billing.fee.reversal-intent.v1"

        /** Mirrors `BillingAssessmentRepositoryImpl.ANNUAL_FEE_SUMMARY_EVENT_TYPE` (ADR-0248). */
        const val ANNUAL_FEE_SUMMARY_EVENT_TYPE = "billing.annual-fee-summary.ready"

        @Suppress("MaxLineLength")
        private const val CLAIM_SQL = """
            UPDATE billing_outbox
            SET status = :dispatching, claimed_at = :now, updated_at = :now
            WHERE id IN (
                SELECT id FROM billing_outbox
                WHERE (status IN (:pending, :failed))
                   OR (status = :dispatching AND claimed_at < :staleThreshold)
                ORDER BY created_at ASC
                LIMIT :claimLimit
                FOR UPDATE SKIP LOCKED
            )
            RETURNING *
        """
    }
}

/** Reads only the one field this repository needs from the `billing.fee.post-intent.v1` payload. */
@JsonIgnoreProperties(ignoreUnknown = true)
private data class IdempotencyKeyOnly(val idempotencyKey: String)

/** Reads only the one field this repository needs from the `billing.fee.reversal-intent.v1` payload. */
@JsonIgnoreProperties(ignoreUnknown = true)
private data class OriginalIdempotencyKeyOnly(val originalIdempotencyKey: String)
