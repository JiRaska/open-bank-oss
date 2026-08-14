// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.productcatalog.domain.catalog.CatalogChangeEvent
import io.smallrye.mutiny.Uni
import io.vertx.core.json.JsonObject
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Writes compatibility audit evidence and its event atomically with the state change. */
@ApplicationScoped
class CatalogCompatibilityEvidenceWriter(private val mapper: ObjectMapper, private val clock: Clock) {
    fun record(
        session: Mutiny.Session,
        aggregateType: String,
        aggregateId: UUID,
        action: String,
        actorId: String,
    ): Uni<Void> {
        val at = Instant.now(clock)
        val event = CatalogChangeEvent(
            eventId = Ids.newId(),
            aggregateType = aggregateType,
            aggregateId = aggregateId,
            eventType = "com.openbank.catalog.${action.lowercase()}",
            schemaVersion = 1,
            occurredAt = at,
            actorId = actorId,
        )
        val audit = CatalogAuditEntity().apply {
            id = Ids.newId()
            this.aggregateType = aggregateType
            this.aggregateId = aggregateId
            this.action = action
            this.actorId = actorId
            occurredAt = at
            details = JsonObject(mapOf("action" to action))
        }
        val outbox = CatalogOutboxEntity().apply {
            id = event.eventId
            this.aggregateType = aggregateType
            this.aggregateId = aggregateId
            eventType = event.eventType
            schemaVersion = event.schemaVersion
            occurredAt = at
            payload = JsonObject(mapper.writeValueAsString(event))
            headers = JsonObject(
                mapOf(
                    "ce_specversion" to "1.0",
                    "ce_id" to event.eventId.toString(),
                    "ce_source" to "openbank-product-catalog",
                    "ce_type" to event.eventType,
                    "content-type" to "application/json",
                ),
            )
            createdAt = at
        }
        return session.persistAll(audit, outbox)
    }
}
