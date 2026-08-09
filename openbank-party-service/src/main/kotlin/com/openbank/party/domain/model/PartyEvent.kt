// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.domain.model

import java.time.Instant
import java.util.UUID

/**
 * A party lifecycle event, together with the exact flat JSON envelope that goes on the wire
 * (topic `openbank.party.events`).
 *
 * [envelope] is deliberately the whole message body rather than a set of typed fields: the
 * envelope IS the contract downstream services parse (account-service opens an account on
 * `PARTY_CREATED`, aml/kyc/onboarding read the same field names), and it is pinned by
 * `PartyEventEnvelopeContractTest` and by the provider-side pacts. Serialization happens in the
 * infrastructure layer — this stays framework-free.
 */
data class PartyEvent(
    val eventType: String,
    val aggregateId: UUID,
    val occurredAt: Instant,
    val envelope: Map<String, Any?>,
)

/**
 * Builds the party lifecycle events.
 *
 * These used to be built inside `KafkaPartyEventPublisher`, a bare `@Channel("party-events-out")`
 * emitter that fired AFTER the repository transaction had already committed — a dual write. The
 * events now travel through `party_outbox`, written in the same transaction as the state change
 * (issue #4007), so the envelope construction had to move somewhere both the use case and the
 * repository can see. Field names, field order and the flat (non-nested) shape are unchanged, and
 * both channels publish to the same topic, so no consumer sees a difference.
 */
object PartyEvents {

    fun created(party: Party, at: Instant): PartyEvent = lifecycle("PARTY_CREATED", party, at)

    fun updated(party: Party, at: Instant): PartyEvent = lifecycle("PARTY_UPDATED", party, at)

    fun kycStatusChanged(party: Party, at: Instant): PartyEvent = lifecycle("KYC_STATUS_CHANGED", party, at)

    fun erased(partyId: UUID, at: Instant): PartyEvent = PartyEvent(
        eventType = "PARTY_ERASED",
        aggregateId = partyId,
        occurredAt = at,
        envelope = linkedMapOf(
            "eventType" to "PARTY_ERASED",
            "partyId" to partyId,
            "erasedAt" to at,
        ),
    )

    /**
     * ADR-0179: [merged] is the retired duplicate (status MERGED); [survivingPartyId] is the party
     * consumers should follow from now on.
     */
    fun merged(merged: Party, survivingPartyId: UUID, at: Instant): PartyEvent = PartyEvent(
        eventType = "PARTY_MERGED",
        aggregateId = merged.id,
        occurredAt = at,
        envelope = linkedMapOf(
            "eventType" to "PARTY_MERGED",
            "partyId" to merged.id,
            "mergedIntoPartyId" to survivingPartyId,
            "status" to merged.status,
            "occurredAt" to at,
        ),
    )

    private fun lifecycle(eventType: String, party: Party, at: Instant): PartyEvent = PartyEvent(
        eventType = eventType,
        aggregateId = party.id,
        occurredAt = at,
        envelope = linkedMapOf(
            "eventType" to eventType,
            "partyId" to party.id,
            "partyType" to party.partyType,
            "status" to party.status,
            "kycStatus" to party.kycStatus,
            "legalName" to party.legalName,
            "email" to party.email,
            "occurredAt" to at,
        ),
    )
}
