// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.e2e

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * The business half of the onboarding cockpit (ADR-0284 D6): kyb-service events on
 * `openbank.kyb.events` project one row per CASE (never per party) into the board columns
 * STARTED → AWAITING_INITIATOR → AWAITING_SIGNATURES → AWAITING_CHECKS → ACTIVE, with
 * MANUAL_REVIEW surfacing verbatim as NEEDS_REVIEW. Unknown case statuses are NOT guessed onto
 * a neighbouring column — a guessed board column is worse than a missing row — so an
 * unrecognised status leaves no row at all, which the suite proves over JDBC and HTTP.
 */
@QuarkusTest
@TestProfile(OnboardingE2EProfile::class)
class OnboardingBusinessE2E : OnboardingE2EBase() {

    @Test
    @TestSecurity(user = "e2e-viewer", roles = ["ROLE_VIEWER"])
    fun `a KYB case walks the board columns from STARTED through NEEDS_REVIEW with the reason verbatim`() {
        val caseId = UUID.randomUUID()
        val initiator = UUID.randomUUID()
        publishKyb(caseId, initiator, "IDENTIFIER_ENTERED", emptyMap())

        val started = awaitBusinessCase(caseId)
        assertThat(started.stage).isEqualTo("STARTED")
        assertThat(started.caseStatus).isEqualTo("IDENTIFIER_ENTERED")
        assertThat(started.legalName).isEqualTo("E2E Synthetic s.r.o.")

        // The case is served by id over HTTP.
        val served = getJson("/api/v1/onboarding/business/cases/$caseId", 200)
        assertThat(served.get("stage").asText()).isEqualTo("STARTED")
        assertThat(served.get("initiatorPartyId").asText()).isEqualTo(initiator.toString())

        publishKyb(caseId, initiator, "REGISTRY_VERIFIED", emptyMap())
        assertThat(awaitBusinessCase(caseId).stage).isEqualTo("AWAITING_INITIATOR")

        publishKyb(caseId, initiator, "READY_TO_SIGN", mapOf("requiredSignatures" to 2))
        val signing = awaitBusinessCase(caseId)
        assertThat(signing.stage).isEqualTo("AWAITING_SIGNATURES")

        publishKyb(caseId, initiator, "SIGNED", mapOf("requiredSignatures" to 2, "signedCount" to 2))
        val signed = awaitBusinessCase(caseId)
        assertThat(signed.stage).isEqualTo("AWAITING_CHECKS")
        assertThat(signed.signedCount).isEqualTo(2)

        // The only column that is work for the bank — and the reason is never paraphrased.
        publishKyb(
            caseId,
            initiator,
            "MANUAL_REVIEW",
            mapOf("reviewReason" to "initiator matched two registry entries"),
        )
        val review = awaitBusinessCase(caseId)
        assertThat(review.stage).isEqualTo("NEEDS_REVIEW")
        assertThat(review.reviewReason).isEqualTo("initiator matched two registry entries")

        publishKyb(caseId, initiator, "ACTIVE", mapOf("signedCount" to 2))
        assertThat(awaitBusinessCase(caseId).stage).isEqualTo("ACTIVE")

        // The KPI tiles carry every column including zero ones, and NEEDS_REVIEW counts the case.
        val funnel = getJson("/api/v1/onboarding/business/funnel", 200)
        assertThat(funnel.get("stages").properties().map { it.key })
            .contains("STARTED", "NEEDS_REVIEW", "CLOSED")
        assertThat(funnel.get("stages").get("ACTIVE").asLong()).isGreaterThanOrEqualTo(1)
    }

    @Test
    @TestSecurity(user = "e2e-viewer", roles = ["ROLE_VIEWER"])
    fun `replaying the same KYB event updates the single row instead of duplicating it`() {
        val caseId = UUID.randomUUID()
        val initiator = UUID.randomUUID()
        publishKyb(caseId, initiator, "IDENTIFIER_ENTERED", emptyMap())
        awaitBusinessCase(caseId)

        // The same event again — case_id is UNIQUE and the projection upserts on it.
        publishKyb(caseId, initiator, "IDENTIFIER_ENTERED", emptyMap())
        Thread.sleep(2_000)
        assertThat(businessRowCount(caseId)).isEqualTo(1)

        publishKyb(caseId, initiator, "REGISTRY_VERIFIED", emptyMap())
        assertThat(awaitBusinessCase(caseId).stage).isEqualTo("AWAITING_INITIATOR")
        assertThat(businessRowCount(caseId)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "e2e-viewer", roles = ["ROLE_VIEWER"])
    fun `an unknown KYB status is not guessed onto the board - no row appears`() {
        val caseId = UUID.randomUUID()
        val initiator = UUID.randomUUID()
        publishKyb(caseId, initiator, "REGISTER_UNREACHABLE_TODAY", emptyMap())

        // The consumer acks the record as UNRECOGNISED — assert the absence landed.
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (System.nanoTime() < deadline && businessRowCount(caseId) == 0) {
            Thread.sleep(150)
        }
        assertThat(businessRowCount(caseId)).isEqualTo(0)
        getJson("/api/v1/onboarding/business/cases/$caseId", 404)

        // And the board does not contain it under any column.
        val cases = getJson("/api/v1/onboarding/business/cases?size=100", 200)
        assertThat(cases.isArray).isTrue()
        assertThat(cases.map { it.get("caseId").asText() }).doesNotContain(caseId.toString())
    }

    @Test
    @TestSecurity(user = "e2e-viewer", roles = ["ROLE_VIEWER"])
    fun `the business list pins its negative paths - typo stage 400, unknown case 404`() {
        io.restassured.module.kotlin.extensions.Given { this }
            .`when`().get("/api/v1/onboarding/business/cases?stage=NEEDS_REVIW")
            .then().statusCode(400)

        val missing = getJson("/api/v1/onboarding/business/cases/${UUID.randomUUID()}", 404)
        assertThat(missing.get("code").asText()).isEqualTo("NOT_FOUND")
    }

    // ── publish helpers ───────────────────────────────────────────────────────

    private fun publishKyb(caseId: UUID, initiator: UUID, status: String, extra: Map<String, Any?>) {
        publish(
            OnboardingKafkaTestResource.KYB_TOPIC,
            OnboardingKafkaTestResource.KYB_GROUP,
            caseId,
            buildMap {
                put("eventType", "KYB_CASE_$status")
                put("caseId", caseId.toString())
                put("status", status)
                put("identifierScheme", "CZ_ICO")
                put("identifier", "12345678")
                put("legalName", "E2E Synthetic s.r.o.")
                put("country", "CZ")
                put("initiatorPartyId", initiator.toString())
                put("occurredAt", now())
                putAll(extra)
            },
        )
    }

    private fun businessRowCount(caseId: UUID): Int = dataSource.connection.use { conn ->
        val ps = conn.prepareStatement("SELECT count(*) FROM business_onboarding_records WHERE case_id = ?")
        ps.setObject(1, caseId)
        val rs = ps.executeQuery()
        rs.next()
        rs.getInt(1)
    }
}
