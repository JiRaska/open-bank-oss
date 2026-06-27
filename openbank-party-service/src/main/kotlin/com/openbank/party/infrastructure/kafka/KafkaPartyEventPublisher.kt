// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.party.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.party.application.port.out.PartyEventPublisher
import com.openbank.party.domain.model.Party
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class KafkaPartyEventPublisher : PartyEventPublisher {

    @Inject
    @Channel("party-events-out")
    lateinit var emitter: Emitter<String>

    @Inject lateinit var objectMapper: ObjectMapper

    @Inject lateinit var clock: Clock

    override suspend fun publishPartyCreated(party: Party) = publish("PARTY_CREATED", party)
    override suspend fun publishPartyUpdated(party: Party) = publish("PARTY_UPDATED", party)
    override suspend fun publishKycStatusChanged(party: Party) = publish("KYC_STATUS_CHANGED", party)

    override suspend fun publishPartyErased(id: UUID) {
        val event = mapOf(
            "eventType" to "PARTY_ERASED",
            "partyId" to id,
            "erasedAt" to Instant.now(clock),
        )
        emitter.send(objectMapper.writeValueAsString(event))
    }

    private fun publish(eventType: String, party: Party) {
        val event = mapOf(
            "eventType" to eventType,
            "partyId" to party.id,
            "partyType" to party.partyType,
            "status" to party.status,
            "kycStatus" to party.kycStatus,
            "legalName" to party.legalName,
            "email" to party.email,
            "occurredAt" to Instant.now(clock),
        )
        emitter.send(objectMapper.writeValueAsString(event))
    }
}
