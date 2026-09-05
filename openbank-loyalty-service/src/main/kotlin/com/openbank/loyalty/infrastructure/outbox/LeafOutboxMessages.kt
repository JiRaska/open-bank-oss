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
 * Builds the wire payload for every ledger event. Separated from the repository so the adapter
 * keeps one job (writing rows in a transaction) and the event vocabulary has one home — the shape
 * of what leaves this service is worth reading in a single file rather than reconstructing from
 * three inline builders.
 *
 * Every payload carries `actorType=SYSTEM` via [EventActor], the fleet's shared "no person
 * originated this" vocabulary, rather than a locally invented sentinel.
 */
@ApplicationScoped
class LeafOutboxMessages(private val mapper: ObjectMapper) {

    fun earned(entry: LeafLedgerEntry): OutboxMessage = OutboxMessage(
        eventId = Ids.newId(),
        aggregateId = entry.partyId,
        eventType = "LeafEarned.${entry.earnSource?.id}",
        payload = mapper.writeValueAsString(
            base(entry) + mapOf(
                "earnSourceId" to entry.earnSource?.id,
                "expiresAt" to entry.expiresAt?.toString(),
            ),
        ),
        createdAt = entry.occurredAt,
    )

    fun benefitGranted(grant: BenefitGrant, burn: LeafLedgerEntry): OutboxMessage = OutboxMessage(
        eventId = Ids.newId(),
        aggregateId = grant.partyId,
        eventType = "LeafBenefitGranted.${grant.benefitId}",
        payload = mapper.writeValueAsString(
            base(burn) + mapOf(
                "grantId" to grant.id.toString(),
                "benefitId" to grant.benefitId,
                // GRANTED means owed and published, never applied. The delivering engine reports
                // application; no field here may be read as its receipt.
                "grantStatus" to grant.status.name,
                "grantExpiresAt" to grant.expiresAt?.toString(),
            ),
        ),
        createdAt = burn.occurredAt,
    )

    fun expired(entry: LeafLedgerEntry): OutboxMessage = OutboxMessage(
        eventId = Ids.newId(),
        aggregateId = entry.partyId,
        eventType = "LeafExpired",
        payload = mapper.writeValueAsString(base(entry)),
        createdAt = entry.occurredAt,
    )

    private fun base(entry: LeafLedgerEntry): Map<String, Any?> = mapOf(
        "entryId" to entry.id.toString(),
        "aggregateType" to AGGREGATE_TYPE,
        "aggregateId" to entry.partyId.toString(),
        "partyId" to entry.partyId.toString(),
        "entryType" to entry.type.name,
        "leaves" to entry.leaves.value,
        "ruleVersion" to entry.ruleVersion,
        "correlationEventId" to entry.correlationEventId.toString(),
        "occurredAt" to entry.occurredAt.toString(),
        EventActor.FIELD_ACTOR_TYPE to EventActor.TYPE_SYSTEM,
        EventActor.FIELD_ACTOR_ID to EventActor.system(SERVICE_NAME, MECHANISM),
    )

    private companion object {
        const val AGGREGATE_TYPE = "LEAF_LEDGER"
        const val SERVICE_NAME = "loyalty"
        const val MECHANISM = "leaf-ledger"
    }
}
