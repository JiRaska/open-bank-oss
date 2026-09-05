// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.infrastructure.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.event.EventActor
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.loyalty.domain.BenefitGrant
import com.openbank.loyalty.domain.LeafLedgerEntry
import jakarta.enterprise.context.ApplicationScoped

/**
 * Builds the wire payload for every ledger event.
 *
 * Each message is composed IN FULL at its own call site, with the shared fields repeated rather
 * than folded into a helper. That is deliberate and it is not a style preference. Nothing
 * validates a Kafka payload at runtime here — no topic has a registered schema — so
 * `openbank-contracts/openbank-loyalty-service/asyncapi.yaml` is the only description a consumer
 * author can read, and `check-event-contract-code-agreement.py` is the only thing that keeps that
 * description true. It reads the field names out of the construction site, so a payload assembled
 * from a helper elsewhere in the file yields a PARTIAL key set that reads as a complete one: the
 * gate would report the shared fields as documented-but-not-sent while the producer sends them
 * perfectly well. Writing each message whole keeps the check honest, and it also means the wire
 * shape of one event is readable in one place, which is what the contract documents.
 *
 * `buildMap { put(...) }` rather than `mapOf`: it is the fleet idiom for an outbox payload, and it
 * is the shape that lets the actor attribution use [EventActor]'s shared constants — the canonical
 * spelling for "no person originated this" — instead of a locally invented string.
 */
@ApplicationScoped
class LeafOutboxMessages(private val mapper: ObjectMapper) {

    fun earned(entry: LeafLedgerEntry): OutboxMessage = OutboxMessage(
        eventId = Ids.newId(),
        aggregateId = entry.partyId,
        eventType = "LeafEarned.${entry.earnSource?.id}",
        payload = mapper.writeValueAsString(
            buildMap {
                put("entryId", entry.id.toString())
                put("aggregateType", AGGREGATE_TYPE)
                put("aggregateId", entry.partyId.toString())
                put("partyId", entry.partyId.toString())
                put("entryType", entry.type.name)
                put("leaves", entry.leaves.value)
                put("ruleVersion", entry.ruleVersion)
                put("correlationEventId", entry.correlationEventId.toString())
                put("occurredAt", entry.occurredAt.toString())
                put("sourceService", SOURCE_SERVICE)
                put(EventActor.FIELD_ACTOR_TYPE, EventActor.TYPE_SYSTEM)
                put(EventActor.FIELD_ACTOR_ID, EventActor.system(SERVICE_NAME, MECHANISM))
                put("earnSourceId", entry.earnSource?.id)
                put("expiresAt", entry.expiresAt?.toString())
            },
        ),
        createdAt = entry.occurredAt,
    )

    fun benefitGranted(grant: BenefitGrant, burn: LeafLedgerEntry): OutboxMessage = OutboxMessage(
        eventId = Ids.newId(),
        aggregateId = grant.partyId,
        eventType = "LeafBenefitGranted.${grant.benefitId}",
        payload = mapper.writeValueAsString(
            buildMap {
                put("entryId", burn.id.toString())
                put("aggregateType", AGGREGATE_TYPE)
                put("aggregateId", burn.partyId.toString())
                put("partyId", burn.partyId.toString())
                put("entryType", burn.type.name)
                put("leaves", burn.leaves.value)
                put("ruleVersion", burn.ruleVersion)
                put("correlationEventId", burn.correlationEventId.toString())
                put("occurredAt", burn.occurredAt.toString())
                put("sourceService", SOURCE_SERVICE)
                put(EventActor.FIELD_ACTOR_TYPE, EventActor.TYPE_SYSTEM)
                put(EventActor.FIELD_ACTOR_ID, EventActor.system(SERVICE_NAME, MECHANISM))
                put("grantId", grant.id.toString())
                put("benefitId", grant.benefitId)
                // GRANTED means owed and published, never applied. The delivering engine reports
                // application; no field here may be read as its receipt.
                put("grantStatus", grant.status.name)
                put("grantExpiresAt", grant.expiresAt?.toString())
            },
        ),
        createdAt = burn.occurredAt,
    )

    fun expired(entry: LeafLedgerEntry): OutboxMessage = OutboxMessage(
        eventId = Ids.newId(),
        aggregateId = entry.partyId,
        eventType = "LeafExpired",
        payload = mapper.writeValueAsString(
            buildMap {
                put("entryId", entry.id.toString())
                put("aggregateType", AGGREGATE_TYPE)
                put("aggregateId", entry.partyId.toString())
                put("partyId", entry.partyId.toString())
                put("entryType", entry.type.name)
                put("leaves", entry.leaves.value)
                put("ruleVersion", entry.ruleVersion)
                put("correlationEventId", entry.correlationEventId.toString())
                put("occurredAt", entry.occurredAt.toString())
                // audit-service attributes a row by this field when present and DERIVES it from
                // the topic name when absent. `audit_entries` is append-only at the database and
                // `source_service` is chain-hashed into `record_hash`, so a row attributed by
                // derivation can never be corrected (#5256/#6035).
                put("sourceService", SOURCE_SERVICE)
                put(EventActor.FIELD_ACTOR_TYPE, EventActor.TYPE_SYSTEM)
                put(EventActor.FIELD_ACTOR_ID, EventActor.system(SERVICE_NAME, MECHANISM))
            },
        ),
        createdAt = entry.occurredAt,
    )

    private companion object {
        const val AGGREGATE_TYPE = "LEAF_LEDGER"
        const val SOURCE_SERVICE = "loyalty-service"
        const val SERVICE_NAME = "loyalty"
        const val MECHANISM = "leaf-ledger"
    }
}
