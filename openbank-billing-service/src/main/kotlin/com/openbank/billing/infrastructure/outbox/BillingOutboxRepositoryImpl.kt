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
import java.time.Instant
import java.util.UUID

/**
 * [OutboxRepository] for `billing_outbox` (ADR-0143 phase 2c). Mirrors
 * `InterestOutboxRepositoryImpl` column-for-column; the one addition is [markFailed] also
 * flipping the originating [com.openbank.billing.domain.AssessedFee] to
 * [com.openbank.billing.domain.PostingStatus.FAILED] once the row goes terminal DEAD — so a
 * poison fee-posting is operator-visible on the fee itself, not only in the outbox table.
 */
@ApplicationScoped
class BillingOutboxRepositoryImpl(private val assessments: BillingAssessmentRepository) :
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

    override suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant) {
        val deadRowIdempotencyKey: Uni<String?> = Panache.withTransaction {
            find("eventId", eventId).firstResult().map { e ->
                if (e == null) {
                    null
                } else {
                    applyFailure(e, error, failedAt)
                    if (e.status == OutboxStatus.DEAD.name) extractIdempotencyKey(e.payload) else null
                }
            }
        }
        // Outside the outbox row's own transaction (deliberately — a fee's posting_status is a
        // separate aggregate from the outbox row; both updates are individually durable, and a
        // crash between them just means the fee catches up to FAILED on a later markFailed retry
        // or is visible as "PENDING forever" — never silently POSTED).
        deadRowIdempotencyKey.awaitSuspending()?.let { assessments.markFailed(it) }
    }

    /**
     * Proper Jackson deserialization (fix-review finding) rather than a regex against raw JSON —
     * [LedgerOutboxEventPublisher] already deserializes this same `billing.fee.post-intent.v1`
     * payload shape via Jackson; reusing that approach here keeps both readers equally robust to
     * whitespace/field-order/escaping rather than depending on a hand-rolled pattern.
     */
    private fun extractIdempotencyKey(payload: String): String? =
        runCatching { mapper.readValue(payload, IdempotencyKeyOnly::class.java).idempotencyKey }.getOrNull()

    /** Record a publish failure (ADR-0050 N5) — same policy every service's outbox repo applies. */
    private fun applyFailure(e: BillingOutboxEntity, error: String, at: Instant) {
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
    }

    companion object {
        private val log: Logger = Logger.getLogger(BillingOutboxRepositoryImpl::class.java)
    }
}

/** Reads only the one field this repository needs from the `billing.fee.post-intent.v1` payload. */
@JsonIgnoreProperties(ignoreUnknown = true)
private data class IdempotencyKeyOnly(val idempotencyKey: String)
