// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.event

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.delegation.domain.model.ApprovalKind
import com.openbank.delegation.domain.model.ApprovalStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ApprovalLifecycleWireContractTest {

    @Test
    fun `every approval lifecycle event preserves the notification contract fields on the wire`() {
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val cases = listOf(
            Triple(ApprovalRequested::class.java, ApprovalRequested.EVENT_TYPE, ApprovalStatus.PENDING),
            Triple(ApprovalSigned::class.java, ApprovalSigned.EVENT_TYPE, ApprovalStatus.PENDING),
            Triple(ApprovalCompleted::class.java, ApprovalCompleted.EVENT_TYPE, ApprovalStatus.APPROVED),
            Triple(ApprovalRejected::class.java, ApprovalRejected.EVENT_TYPE, ApprovalStatus.REJECTED),
            Triple(ApprovalExpired::class.java, ApprovalExpired.EVENT_TYPE, ApprovalStatus.EXPIRED),
            Triple(PaymentReleased::class.java, PaymentReleased.EVENT_TYPE, ApprovalStatus.RELEASED),
            Triple(PaymentReleaseFailed::class.java, PaymentReleaseFailed.EVENT_TYPE, ApprovalStatus.RELEASE_FAILED),
        )
        val aggregateId = UUID.fromString("00000000-0000-4000-8000-000000000001")
        val entityPartyId = UUID.fromString("00000000-0000-4000-8000-000000000002")
        val initiatorPartyId = UUID.fromString("00000000-0000-4000-8000-000000000003")
        val signerPartyId = UUID.fromString("00000000-0000-4000-8000-000000000004")

        for ((eventClass, eventType, status) in cases) {
            val fields = linkedMapOf<String, Any>(
                "aggregateId" to aggregateId,
                "entityPartyId" to entityPartyId,
                "kind" to ApprovalKind.PAYMENT,
                "status" to status,
                "requiredSignatures" to 2,
                "collectedSignatures" to 1,
                "initiatorPartyId" to initiatorPartyId,
                "actorPartyId" to signerPartyId,
                "recipientPartyIds" to listOf(initiatorPartyId, signerPartyId),
                "signerPartyIds" to listOf(signerPartyId),
                "expiresAt" to Instant.parse("2026-09-29T12:00:00Z"),
                "payloadSha256" to "a".repeat(64),
                "summary" to mapOf("paymentReference" to "fixture-payment"),
                "reason" to "fixture-reason",
                "releaseRef" to "fixture-release",
                "occurredAt" to Instant.parse("2026-09-28T12:00:00Z"),
                "approvalId" to aggregateId,
                "type" to eventType,
                "entityName" to "Example entity",
                "initiatorName" to "Example initiator",
                "amount" to "25.00",
                "currency" to "EUR",
                "payeeName" to "Example payee",
            )
            val event = mapper.convertValue(fields, eventClass)
            val wire = mapper.valueToTree<JsonNode>(event)

            for ((field, value) in fields) {
                assertThat(wire.get(field))
                    .describedAs("$eventType.$field")
                    .isEqualTo(mapper.valueToTree<JsonNode>(value))
            }
            assertThat(wire.get("eventType").asText()).isEqualTo(eventType)
            assertThat(wire.get("aggregateType").asText()).isEqualTo("ApprovalRequest")
            assertThat(wire.get("version").asLong()).isEqualTo(1L)
            assertThat(wire.get("schemaVersion").asInt()).isEqualTo(SCHEMA_VERSION)
            assertThat(wire.get("sourceService").asText()).isEqualTo("delegation-service")
        }
    }
}
