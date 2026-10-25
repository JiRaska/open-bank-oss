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
 * GDPR Art. 17 erasure across the real pipeline: PARTY_ERASED on `openbank.party.events` must
 * strip the personal data from the read model. The platform's erasure contract is anonymisation
 * of the projection row (legal name and email nulled, the aggregate row itself kept so funnel
 * counts stay consistent) — asserted over both JDBC and HTTP. The consumer rethrows projection
 * failures instead of acking them precisely because an erasure that is acked without happening
 * is a compliance breach no later process notices.
 */
@QuarkusTest
@TestProfile(OnboardingE2EProfile::class)
class OnboardingErasureE2E : OnboardingE2EBase() {

    @Test
    @TestSecurity(user = "e2e-viewer", roles = ["ROLE_VIEWER"])
    fun `PARTY_ERASED anonymises the projected record - PII is null over JDBC and HTTP`() {
        val partyId = UUID.randomUUID()
        publish(
            OnboardingKafkaTestResource.PARTY_TOPIC,
            OnboardingKafkaTestResource.PARTY_GROUP,
            partyId,
            mapOf(
                "eventType" to "PARTY_CREATED",
                "partyId" to partyId.toString(),
                "legalName" to "E2E Erasure Person",
                "email" to "e2e-erasure-$partyId@example.test",
                "occurredAt" to now(),
            ),
        )
        val before = awaitPersonalRecord(partyId)
        assertThat(before.funnelStage).isEqualTo("REGISTERED")

        publish(
            OnboardingKafkaTestResource.PARTY_TOPIC,
            OnboardingKafkaTestResource.PARTY_GROUP,
            partyId,
            mapOf("eventType" to "PARTY_ERASED", "partyId" to partyId.toString()),
        )
        val erased = awaitAnonymised(partyId)

        // The aggregate row survives (funnel counts stay consistent), the PII does not.
        assertThat(erased.funnelStage).isEqualTo("REGISTERED")
        assertThat(erased.partyStatus).isEqualTo("PENDING_KYC")

        // The same anonymised state is served over HTTP: 200 with null PII fields.
        val record = getRecordViaApi(partyId)
        assertThat(record.get("legalName").isNull).isTrue()
        assertThat(record.get("email").isNull).isTrue()
        assertThat(record.get("partyId").asText()).isEqualTo(partyId.toString())
    }

    /** The erasure is acknowledged by the consumer group but lands asynchronously — poll for it. */
    private fun awaitAnonymised(partyId: UUID): PersonalRow {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            val row = personalRow(partyId)
            if (row != null && isAnonymised(partyId)) return row
            Thread.sleep(150)
        }
        error("onboarding read model still holds PII for erased party $partyId")
    }

    // The JDBC row is anonymised when both PII columns are NULL; PersonalRow does not carry
    // them, so re-read the two columns directly.
    private fun isAnonymised(partyId: UUID): Boolean = dataSource.connection.use { conn ->
        val ps = conn.prepareStatement(
            "SELECT legal_name IS NULL AND email IS NULL FROM onboarding_records WHERE party_id = ?",
        )
        ps.setObject(1, partyId)
        val rs = ps.executeQuery()
        rs.next() && rs.getBoolean(1)
    }
}
