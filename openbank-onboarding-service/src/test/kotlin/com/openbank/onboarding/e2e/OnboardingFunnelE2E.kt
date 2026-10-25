// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.e2e

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The personal onboarding funnel (ADR-0068) over real HTTP + Postgres/Redpanda: party events
 * project the read model, kyc events move the funnel column, activation without a device is
 * SCA_PENDING until the first enrolment, suspension blocks. The negative paths are pinned too:
 * a mistyped `?stage=` widens nothing (#8699 → 400), an unknown party answers 404, the page
 * size is clamped to the published 1..100 window, and a role-less caller is refused.
 */
@QuarkusTest
@TestProfile(OnboardingE2EProfile::class)
class OnboardingFunnelE2E : OnboardingE2EBase() {

    @Test
    @TestSecurity(user = "e2e-viewer", roles = ["ROLE_VIEWER"])
    fun `PARTY_CREATED lands the record in REGISTERED and the KPI tiles count it`() {
        val partyId = UUID.randomUUID()
        publish(
            OnboardingKafkaTestResource.PARTY_TOPIC,
            OnboardingKafkaTestResource.PARTY_GROUP,
            partyId,
            mapOf(
                "eventType" to "PARTY_CREATED",
                "partyId" to partyId.toString(),
                "legalName" to "E2E Funnel Person",
                "email" to "e2e-funnel-$partyId@example.test",
                "occurredAt" to now(),
            ),
        )

        // A brand-new party sits in the first funnel column; the KYC column is for a case that
        // has actually been opened (the next journey publishes KYC_CASE_OPENED).
        val row = awaitPersonalRecord(partyId)
        assertThat(row.funnelStage).isEqualTo("REGISTERED")
        assertThat(row.partyStatus).isEqualTo("PENDING_KYC")

        // The same state is served over HTTP, PII fields carried verbatim.
        val record = getRecordViaApi(partyId)
        assertThat(record.get("funnelStage").asText()).isEqualTo("REGISTERED")
        assertThat(record.get("legalName").asText()).isEqualTo("E2E Funnel Person")
        assertThat(record.get("scaEnrolled").asBoolean()).isFalse()

        // KPI tiles: every stage present (a missing stage reads exactly like a broken projection),
        // the new record counted in REGISTERED.
        val funnel = getJson("/api/v1/onboarding/funnel", 200)
        assertThat(funnel.get("REGISTERED").asLong()).isGreaterThanOrEqualTo(1)
        assertThat(funnel.properties().map { it.key })
            .contains("KYC_OPEN", "ACTIVE", "BLOCKED")

        // The list filter matches the record by stage and excludes it for other stages.
        val openPage = getJson("/api/v1/onboarding/records?stage=REGISTERED&size=100", 200)
        val ids = openPage.get("items").map { it.get("partyId").asText() }
        assertThat(ids).contains(partyId.toString())
        val activePage = getJson("/api/v1/onboarding/records?stage=ACTIVE&size=100", 200)
        assertThat(activePage.get("items").map { it.get("partyId").asText() })
            .doesNotContain(partyId.toString())
    }

    @Test
    @TestSecurity(user = "e2e-viewer", roles = ["ROLE_VIEWER"])
    fun `the funnel walks KYC_UNDER_REVIEW to SCA_PENDING to ACTIVE as upstream events arrive`() {
        val partyId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        publishParty(partyId, createdEvent("E2E Journey Person"))
        awaitPersonalRecord(partyId)

        publishKyc(
            caseId,
            partyId,
            "KYC_CASE_OPENED",
            mapOf("kycCaseId" to caseId.toString()),
        )
        val opened = awaitPersonalRecord(partyId)
        assertThat(opened.kycCaseId).isEqualTo(caseId)
        assertThat(opened.funnelStage).isEqualTo("KYC_OPEN")

        // UNDER_REVIEW moves the column; APPROVED must NOT move it backwards or forwards —
        // the party stays in the KYC column until party-service activates it (#8951).
        publishKyc(
            caseId,
            partyId,
            "KYC_CASE_STATUS_CHANGED",
            mapOf("kycCaseId" to caseId.toString(), "status" to "UNDER_REVIEW"),
        )
        assertThat(awaitPersonalRecord(partyId).funnelStage).isEqualTo("KYC_UNDER_REVIEW")
        publishKyc(
            caseId,
            partyId,
            "KYC_CASE_STATUS_CHANGED",
            mapOf("kycCaseId" to caseId.toString(), "status" to "APPROVED"),
        )
        assertThat(awaitPersonalRecord(partyId).funnelStage).isEqualTo("KYC_UNDER_REVIEW")

        // Activation without an enrolled device is SCA_PENDING, not ACTIVE.
        publishParty(partyId, statusEvent("ACTIVE"))
        assertThat(awaitPersonalRecord(partyId).funnelStage).isEqualTo("SCA_PENDING")

        // The first device enrolment activates the record; the second only adds to the ledger.
        publishSca(partyId, "credential-e2e-1")
        val active = awaitPersonalRecord(partyId)
        assertThat(active.funnelStage).isEqualTo("ACTIVE")
        assertThat(active.scaEnrolled).isTrue()
        assertThat(active.deviceCount).isEqualTo(1)
        publishSca(partyId, "credential-e2e-2")
        val twoDevices = awaitPersonalRecord(partyId)
        assertThat(twoDevices.deviceCount).isEqualTo(2)
        assertThat(twoDevices.funnelStage).isEqualTo("ACTIVE")
    }

    @Test
    @TestSecurity(user = "e2e-viewer", roles = ["ROLE_VIEWER"])
    fun `a suspended party reads BLOCKED and a rejected KYC blocks the record`() {
        val suspendedParty = UUID.randomUUID()
        publishParty(suspendedParty, createdEvent("E2E Suspended Person"))
        awaitPersonalRecord(suspendedParty)
        publishParty(suspendedParty, statusEvent("SUSPENDED"))
        assertThat(awaitPersonalRecord(suspendedParty).funnelStage).isEqualTo("BLOCKED")
        assertThat(getRecordViaApi(suspendedParty).get("funnelStage").asText()).isEqualTo("BLOCKED")

        val rejectedParty = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        publishParty(rejectedParty, createdEvent("E2E Rejected Person"))
        awaitPersonalRecord(rejectedParty)
        publishKyc(
            caseId,
            rejectedParty,
            "KYC_CASE_STATUS_CHANGED",
            mapOf("kycCaseId" to caseId.toString(), "status" to "REJECTED"),
        )
        assertThat(awaitPersonalRecord(rejectedParty).funnelStage).isEqualTo("BLOCKED")
    }

    @Test
    @TestSecurity(user = "e2e-viewer", roles = ["ROLE_VIEWER"])
    fun `the negative HTTP paths are pinned - typo stage, unknown party, clamped size, role gate`() {
        // A mistyped stage must 400, never widen the result set (#8699).
        io.restassured.module.kotlin.extensions.Given { this }
            .`when`().get("/api/v1/onboarding/records?stage=KYC_OPEEN")
            .then().statusCode(400)

        // Unknown party: 404 with the NOT_FOUND code.
        val missing = getJson("/api/v1/onboarding/records/${UUID.randomUUID()}", 404)
        assertThat(missing.get("code").asText()).isEqualTo("NOT_FOUND")

        // The page size is clamped to the published 1..100 window before it reaches the query.
        val clamped = getJson("/api/v1/onboarding/records?size=500", 200)
        assertThat(clamped.get("size").asInt()).isEqualTo(100)
    }

    @Test
    @TestSecurity(user = "e2e-intern")
    fun `a caller without the viewer role is refused the PII-bearing list`() {
        io.restassured.module.kotlin.extensions.Given { this }
            .`when`().get("/api/v1/onboarding/records")
            .then().statusCode(403)
    }

    // ── publish helpers ───────────────────────────────────────────────────────

    private fun publishParty(partyId: UUID, body: Map<String, Any?>) {
        publish(
            OnboardingKafkaTestResource.PARTY_TOPIC,
            OnboardingKafkaTestResource.PARTY_GROUP,
            partyId,
            body + ("partyId" to partyId.toString()),
        )
    }

    private fun publishKyc(caseId: UUID, partyId: UUID, eventType: String, extra: Map<String, Any?>) {
        publish(
            OnboardingKafkaTestResource.KYC_TOPIC,
            OnboardingKafkaTestResource.KYC_GROUP,
            caseId,
            buildMap {
                put("eventType", eventType)
                put("partyId", partyId.toString())
                put("occurredAt", now())
                putAll(extra)
            },
        )
    }

    private fun publishSca(partyId: UUID, credentialId: String) {
        publish(
            OnboardingKafkaTestResource.SCA_TOPIC,
            OnboardingKafkaTestResource.SCA_GROUP,
            partyId,
            mapOf(
                "eventType" to "DEVICE_ENROLLED",
                "partyId" to partyId.toString(),
                "credentialId" to credentialId,
                "occurredAt" to now(),
            ),
        )
    }

    private fun statusEvent(status: String): Map<String, Any?> = mapOf(
        "eventType" to "PARTY_STATUS_CHANGED",
        "status" to status,
        "occurredAt" to now(),
    )

    private fun createdEvent(legalName: String): Map<String, Any?> = mapOf(
        "eventType" to "PARTY_CREATED",
        "legalName" to legalName,
        "email" to "e2e-${UUID.randomUUID()}@example.test",
        "occurredAt" to now(),
    )
}
