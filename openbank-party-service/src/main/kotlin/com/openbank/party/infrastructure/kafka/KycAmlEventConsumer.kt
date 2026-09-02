// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.kafka

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.messaging.EventRetry
import com.openbank.party.application.port.`in`.PartyUseCase
import com.openbank.party.domain.model.AmlStatus
import com.openbank.party.domain.model.KycStatus
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Drives the party KYC+AML activation gate from the compliance event streams.
 * party-service is the single authority that decides activation: it records the KYC and AML
 * outcomes and [PartyUseCase] flips the party to ACTIVE only when BOTH clear (two-key gate),
 * or to SUSPENDED on a hard negative.
 *
 * Failure handling (#5698): a MALFORMED event is logged and acked — replaying it fails the same
 * way forever, so it is a genuine poison pill. A failure of party-db is the opposite case and is
 * retried, then RETHROWN so the connector dead-letters.
 *
 * The previous version acked both, on the stated grounds that "kyc-/aml-service remain the source
 * of truth and can be replayed". Nothing replays them: the redrive is a human editing an outbox
 * row, which presupposes someone knows. Swallowing a KYC_CASE_APPROVED here strands the party at
 * PENDING_KYC — its accounts never activate and its welcome bonus never pays — which is exactly the
 * incident #5698 records one step upstream, in kyc-service's own consumer.
 */
@ApplicationScoped
class KycAmlEventConsumer(private val partyUseCase: PartyUseCase, private val objectMapper: ObjectMapper) {
    private val log = Logger.getLogger(KycAmlEventConsumer::class.java)

    @Incoming("kyc-events-in")
    suspend fun consumeKyc(payload: String) {
        try {
            val node = objectMapper.readTree(payload)
            val partyId = node.path("partyId").asUuidOrNull() ?: return
            val kyc = when (node.path("eventType").asText()) {
                "KYC_CASE_APPROVED" -> KycStatus.APPROVED
                "KYC_CASE_REJECTED" -> KycStatus.REJECTED
                else -> return // OPENED / STATUS_CHANGED: no terminal KYC decision yet
            }
            EventRetry.withRetry(log, "KYC decision", partyId) {
                partyUseCase.updateKycStatus(partyId, kyc)
            }
            log.infof("KYC %s applied to party %s", kyc, partyId)
        } catch (e: JacksonException) {
            // Unparseable payload: the poison pill. Acked deliberately.
            log.errorf(e, "Unparseable KYC event, acking: %.300s", payload)
        }
    }

    @Incoming("aml-events-in")
    suspend fun consumeAml(payload: String) {
        try {
            val node = objectMapper.readTree(payload)
            val partyId = node.path("partyId").asUuidOrNull() ?: return
            // aml.case.status_changed.v1 carries newStatus; aml.case.created.v1 is ignored.
            val aml = when (node.path("newStatus").asText().ifBlank { node.path("status").asText() }) {
                "CLEARED" -> AmlStatus.CLEARED
                "BLOCKED" -> AmlStatus.BLOCKED
                else -> return // OPEN / UNDER_REVIEW / ESCALATED: no terminal AML decision yet
            }
            EventRetry.withRetry(log, "AML decision", partyId) {
                partyUseCase.updateAmlStatus(partyId, aml)
            }
            log.infof("AML %s applied to party %s", aml, partyId)
        } catch (e: JacksonException) {
            // Unparseable payload: the poison pill. Acked deliberately.
            log.errorf(e, "Unparseable AML event, acking: %.300s", payload)
        }
    }

    private fun com.fasterxml.jackson.databind.JsonNode.asUuidOrNull(): UUID? =
        runCatching { UUID.fromString(asText()) }.getOrNull()
}
