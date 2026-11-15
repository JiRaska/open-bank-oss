// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.kafka

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.messaging.EventRetry
import com.openbank.party.application.usecase.KybCaseMandateRevocation
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Ends the mandates of a kyb business-onboarding case that ended without onboarding the entity
 * (#16015): `BUSINESS_ONBOARDING_ABANDONED` and `BUSINESS_ONBOARDING_REJECTED` on
 * `openbank.kyb.events`. Every other kyb event type is ignored.
 *
 * Failure handling follows [KycAmlEventConsumer] (#5698): an unparseable payload is a poison pill
 * and is acked; a party-db failure is retried, then RETHROWN, and the channel's configured
 * `failure-strategy` decides what follows. Swallowing it would leave a person able to act for a
 * company whose onboarding was refused, with nothing anywhere to say so.
 *
 * An event with no `entityPartyId` is acked: a case that never created its entity party cannot
 * have granted a mandate on it.
 */
@ApplicationScoped
class KybCaseEventConsumer(private val revocation: KybCaseMandateRevocation, private val objectMapper: ObjectMapper) {
    private val log = Logger.getLogger(KybCaseEventConsumer::class.java)

    @Incoming("kyb-events-in")
    suspend fun consume(payload: String) {
        try {
            val node = objectMapper.readTree(payload)
            val eventType = node.path("eventType").asText()
            if (eventType !in ENDING_EVENTS) return
            val caseId = node.path("caseId").asUuidOrNull() ?: return
            val entityPartyId = node.path("entityPartyId").asUuidOrNull() ?: return
            val revoked = EventRetry.withRetry(log, "kyb case mandate revocation", entityPartyId) {
                revocation.revokeForEndedCase(entityPartyId, caseId, eventType)
            }
            log.infof(
                "kyb case %s %s: revoked %d mandate(s) on party %s",
                caseId,
                eventType,
                revoked.size,
                entityPartyId,
            )
        } catch (e: JacksonException) {
            log.errorf(e, "Unparseable kyb event, acking: %.300s", payload)
        }
    }

    private fun JsonNode.asUuidOrNull(): UUID? = runCatching { UUID.fromString(asText()) }.getOrNull()

    private companion object {
        val ENDING_EVENTS = setOf("BUSINESS_ONBOARDING_ABANDONED", "BUSINESS_ONBOARDING_REJECTED")
    }
}
