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
        return suppressions.save(
            suppression,
            SuppressionCreated(
                aggregateId = suppression.id,
                partyId = suppression.partyId,
                scope = suppression.scope,
                value = suppression.value,
                reason = suppression.reason,
                source = suppression.source,
                occurredAt = now.toInstant(),
            ),
        )
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
            ),
        )
    }
}

class SuppressionNotFoundException(id: UUID) : NoSuchElementException("suppression $id not found")
