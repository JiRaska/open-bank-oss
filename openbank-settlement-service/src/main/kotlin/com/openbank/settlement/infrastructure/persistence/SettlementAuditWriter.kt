// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.settlement.application.port.out.SettlementOutboxRepository
import com.openbank.settlement.infrastructure.persistence.entity.SettlementEntity
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped

/** Serializes a committed state fact; callers must own the settlement transaction. */
@ApplicationScoped
class SettlementAuditWriter(private val outbox: SettlementOutboxRepository, private val objectMapper: ObjectMapper) {
    fun append(entity: SettlementEntity, previousStatus: String?): Uni<Void> {
        val eventId = Ids.newId()
        val occurredAt = entity.updatedAt
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "eventId" to eventId,
                "eventType" to "SETTLEMENT_STATE_CHANGED",
                "schemaVersion" to 1,
                "aggregateType" to "SETTLEMENT",
                "aggregateId" to entity.id,
                "sourceService" to "settlement-service",
                // This is a service-owned state fact, not an assertion about the initiating person.
                "actorType" to "SERVICE",
                "occurredAt" to occurredAt.toString(),
                "previousStatus" to previousStatus,
                "status" to entity.status,
                "protocol" to entity.settlementProtocol,
                "payerAccountId" to entity.payerAccountId,
                "payeeAccountId" to entity.payeeAccountId,
                "amount" to entity.amount.toPlainString(),
                "currency" to entity.currency,
            ),
        )
        return outbox.persistInTransaction(
            OutboxMessage(
                eventId = eventId,
                aggregateId = entity.id,
                eventType = "SETTLEMENT_STATE_CHANGED",
                payload = payload,
                createdAt = occurredAt,
            ),
        )
    }
}
