// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.domain.model

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Serialization round-trip for kyc-service's KYC-case lifecycle events (issue #3994/#5256, fleet
 * follow-up to #5255/#5267/#5329's domestic-payment/account+party/transaction-service fixes).
 *
 * `sourceService` is the strongest (EVENT-sourced) attribution `AuditConsumer` reads
 * (`node.textOrNull("sourceService")`) — before this field existed, `KycEvents.lifecycle`'s
 * envelope carried no such key and an audit row for any of these four event types fell back to
 * the `TopicAttribution` table (correct but TOPIC-sourced, not the producer's own claim, for
 * `openbank.kyc.events` -> `"kyc-service"`).
 *
 * `eventType` (`KYC_CASE_OPENED` etc.) already carried the fleet's SCREAMING_SNAKE_CASE
 * convention and is read verbatim by onboarding-service's `OnboardingEventConsumer` and
 * party-service's `KycAmlEventConsumer` (see `KycEventMessagePactConsumerTest` /
 * `KycAmlEventConsumer.kt`) — unlike #5255's `DomesticPaymentEvents`, it is NOT renamed here.
 * Only `sourceService` — a field nothing else on the fleet reads today — is new.
 */
class KycEventsTest {

    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    private val at: Instant = Instant.parse("2026-08-16T10:00:00Z")

    private fun case(reviewedBy: String? = "analyst.novak@openbank.cz") = KycCase(
        id = UUID.randomUUID(),
        partyId = UUID.randomUUID(),
        status = KycCaseStatus.APPROVED,
        riskLevel = RiskLevel.MEDIUM,
        assignedTo = null,
        checks = emptyList(),
        notes = null,
        reviewedBy = reviewedBy,
        reviewedAt = null,
        expiresAt = null,
        createdAt = at,
        updatedAt = at,
    )

    @Test
    fun `caseOpened envelope carries eventType and sourceService for AuditConsumer attribution`() {
        val event = KycEvents.caseOpened(case(reviewedBy = null), at)

        assertThat(event.eventType).isEqualTo("KYC_CASE_OPENED")
        assertThat(event.envelope["sourceService"]).isEqualTo("kyc-service")

        val node = objectMapper.readTree(objectMapper.writeValueAsString(event.envelope))
        assertThat(node.get("eventType").asText()).isEqualTo("KYC_CASE_OPENED")
        assertThat(node.get("sourceService").asText()).isEqualTo("kyc-service")
    }

    @Test
    fun `caseStatusChanged envelope carries eventType and sourceService for AuditConsumer attribution`() {
        val event = KycEvents.caseStatusChanged(case(), at)

        assertThat(event.envelope["sourceService"]).isEqualTo("kyc-service")

        val node = objectMapper.readTree(objectMapper.writeValueAsString(event.envelope))
        assertThat(node.get("eventType").asText()).isEqualTo("KYC_CASE_STATUS_CHANGED")
        assertThat(node.get("sourceService").asText()).isEqualTo("kyc-service")
    }

    @Test
    fun `caseApproved envelope carries eventType and sourceService for AuditConsumer attribution`() {
        val event = KycEvents.caseApproved(case(), at)

        assertThat(event.envelope["sourceService"]).isEqualTo("kyc-service")

        val node = objectMapper.readTree(objectMapper.writeValueAsString(event.envelope))
        assertThat(node.get("eventType").asText()).isEqualTo("KYC_CASE_APPROVED")
        assertThat(node.get("sourceService").asText()).isEqualTo("kyc-service")
    }

    @Test
    fun `caseRejected envelope carries eventType and sourceService for AuditConsumer attribution`() {
        val event = KycEvents.caseRejected(case(), at)

        assertThat(event.envelope["sourceService"]).isEqualTo("kyc-service")

        val node = objectMapper.readTree(objectMapper.writeValueAsString(event.envelope))
        assertThat(node.get("eventType").asText()).isEqualTo("KYC_CASE_REJECTED")
        assertThat(node.get("sourceService").asText()).isEqualTo("kyc-service")
    }
}
