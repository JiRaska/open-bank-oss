// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import org.apache.kafka.common.header.internals.RecordHeaders
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message
import java.time.Clock
import java.util.UUID

enum class PartyPiiRevealReason { KYC_REVIEW, CUSTOMER_SUPPORT, DATA_CORRECTION }

data class PartyPiiRevealRequest(val reason: PartyPiiRevealReason?)

@ApplicationScoped
class PartyPiiRevealAudit(
    @Channel("party-pii-audit-out") private val emitter: MutinyEmitter<String>,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {
    suspend fun record(partyId: UUID, actorId: String, reason: PartyPiiRevealReason) {
        val eventId = Ids.newId().toString()
        val event = PartyPiiRevealed(eventId, partyId, actorId, reason, clock.instant().toString())
        val payload = mapper.writeValueAsString(event)
        val headers = RecordHeaders()
            .add(OutboxKafkaHeaders.HEADER_EVENT_ID, eventId.toByteArray())
            .add(OutboxKafkaHeaders.HEADER_EVENT_TYPE, event.eventType.toByteArray())
        val metadata = OutgoingKafkaRecordMetadata.builder<String>()
            .withKey(partyId.toString())
            .withHeaders(headers)
            .build()
        // Await broker acknowledgement (acks=all) before releasing the response.
        emitter.sendMessage(Message.of(payload).addMetadata(metadata)).awaitSuspending()
    }
}

private data class PartyPiiRevealed(
    val eventId: String,
    val partyId: UUID,
    val actorId: String,
    val reason: PartyPiiRevealReason,
    val occurredAt: String,
    val eventType: String = EVENT_TYPE,
    val sourceService: String = "party-service",
    val actorType: String = "HUMAN",
) {
    companion object {
        const val EVENT_TYPE = "PARTY_PII_REVEALED"
    }
}
