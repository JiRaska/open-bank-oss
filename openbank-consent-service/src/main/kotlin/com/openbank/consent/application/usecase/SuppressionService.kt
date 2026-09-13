// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.application.usecase

import com.openbank.consent.application.port.out.SuppressionRepository
import com.openbank.consent.domain.event.SuppressionCreated
import com.openbank.consent.domain.event.SuppressionRevoked
import com.openbank.consent.domain.model.Suppression
import com.openbank.consent.domain.model.SuppressionReason
import com.openbank.consent.domain.model.SuppressionScope
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import java.sql.SQLException
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * ADR-0219 D3 suppression lifecycle (#3656 slice 2). Every write publishes its event in the same
 * transaction as the state change (transactional outbox), so the contact-policy gate's
 * invalidation signal can never be lost between the commit and the send.
 */
@ApplicationScoped
class SuppressionService(private val suppressions: SuppressionRepository, private val clock: Clock) {

    suspend fun create(
        partyId: UUID,
        scope: SuppressionScope,
        value: String?,
        reason: SuppressionReason,
        source: String,
        createdBy: String,
    ): Suppression {
        // Idempotent replay (ADR-0293, #8351): one active suppression per (partyId, scope, value).
        // A retried create replays the original row — no duplicate row, no second event. The check
        // runs before any state change; uq_suppressions_active_natural (V8) is the race backstop,
        // recovered below by re-reading the winner's row.
        suppressions.findActiveByParty(partyId)
            .firstOrNull { it.scope == scope && it.value == value }
            ?.let { return it }

        val now = OffsetDateTime.now(clock)
        val suppression = Suppression(
            id = Ids.newId(),
            partyId = partyId,
            scope = scope,
            value = value,
            reason = reason,
            source = source,
            createdBy = createdBy,
            createdAt = now,
            revokedAt = null,
            revokedBy = null,
        )
        return try {
            suppressions.save(
                suppression,
                SuppressionCreated(
                    aggregateId = suppression.id,
                    partyId = suppression.partyId,
                    scope = suppression.scope,
                    value = suppression.value,
                    reason = suppression.reason,
                    source = suppression.source,
                    occurredAt = now.toInstant(),
                    sourceService = "consent-service",
                ),
            )
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Lost the race against a concurrent first create of the same natural key: the winner's
            // row is the correct replay answer. The catch is deliberately wide (Hibernate Reactive
            // wraps the PgException several layers deep) and immediately narrowed by the
            // constraint-name check, so an unrelated unique violation is never swallowed as a replay.
            if (!e.isSuppressionNaturalKeyConflict()) throw e
            suppressions.findActiveByParty(partyId)
                .firstOrNull { it.scope == scope && it.value == value } ?: throw e
        }
    }

    suspend fun listActive(partyId: UUID): List<Suppression> = suppressions.findActiveByParty(partyId)

    suspend fun revoke(id: UUID, revokedBy: String): Suppression {
        val existing = suppressions.findById(id) ?: throw SuppressionNotFoundException(id)
        val revoked = existing.revoke(revokedBy, OffsetDateTime.now(clock))
        return suppressions.update(
            revoked,
            SuppressionRevoked(
                aggregateId = revoked.id,
                partyId = revoked.partyId,
                scope = revoked.scope,
                value = revoked.value,
                occurredAt = revoked.revokedAt!!.toInstant(),
                sourceService = "consent-service",
            ),
        )
    }
}

private const val SQLSTATE_UNIQUE_VIOLATION = "23505"
private const val SUPPRESSION_NATURAL_CONSTRAINT = "uq_suppressions_active_natural"

/**
 * True when the failure chain carries the unique violation of `uq_suppressions_active_natural`
 * (V8). Hibernate Reactive adapts the Vert.x PgException into a plain [SQLException] whose sqlState
 * may or may not survive, so accept either the 23505 sqlState or the "(23505)" marker in the
 * message text — and ALWAYS require the constraint name (same shape as
 * NotificationConsumer.isDeduplicationConflict, #8953).
 */
private fun Throwable.isSuppressionNaturalKeyConflict(): Boolean = generateSequence(this) { it.cause }
    .filterIsInstance<SQLException>()
    .any {
        it.message?.contains(SUPPRESSION_NATURAL_CONSTRAINT) == true &&
            (it.sqlState == SQLSTATE_UNIQUE_VIOLATION || it.message.orEmpty().contains("(23505)"))
    }

class SuppressionNotFoundException(id: UUID) : NoSuchElementException("suppression $id not found")
