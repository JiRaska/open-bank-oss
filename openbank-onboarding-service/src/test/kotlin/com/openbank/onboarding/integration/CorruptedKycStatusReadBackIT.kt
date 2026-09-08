// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.onboarding.integration

import com.openbank.libs.security.Roles
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/**
 * `onboarding_records.kyc_status` is a plain TEXT column (#9038): before this change, reading a
 * row back through `OnboardingRepositoryImpl.toDomain()` parsed it with
 * `runCatching { KycStage.valueOf(it) }.getOrNull()`, so a value that does not match any
 * [com.openbank.onboarding.domain.model.KycStage] silently became a legal `null`.
 *
 * A `null` kycStatus is not neutral: `FunnelStage.derive(_, null, _)` reads it as `KYC_OPEN`, the
 * *earliest* funnel stage. So a corrupted `REJECTED` or `EXPIRED` row — Postgres will happily
 * store a typo or a value from a since-removed enum constant, nothing in the schema constrains it
 * — would silently render as an applicant who had just started, instead of surfacing the
 * corruption. The row cannot arrive that way through this service's own write path (`toEntity()`
 * always writes a real enum's `.name`), so the proof has to write the bad value directly, the way
 * a stale column value or a hand-edited row would.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.onboarding.it.OnboardingPostgresTestResource::class)
class CorruptedKycStatusReadBackIT {

    // Every other integration test in this module shares the same Postgres container and reads
    // through `listAll`/`listByStage` (no partyId scoping), so a row this test plants must not
    // outlive it — a stray corrupted row previously made OnboardingStageFilterIT's "still 200"
    // assertions fail with 422, on an endpoint this test never touched.
    private val plantedPartyIds = mutableListOf<UUID>()

    @AfterEach
    fun cleanUp() {
        jdbc().use { conn ->
            conn.prepareStatement("DELETE FROM onboarding_records WHERE party_id = ?").use { st ->
                for (partyId in plantedPartyIds) {
                    st.setObject(1, partyId)
                    st.addBatch()
                }
                if (plantedPartyIds.isNotEmpty()) st.executeBatch()
            }
        }
        plantedPartyIds.clear()
    }

    @Test
    @TestSecurity(user = "operator", roles = [Roles.OPERATOR])
    fun `a row with an unparseable kyc_status fails loudly instead of reading as KYC_OPEN`() {
        val partyId = UUID.randomUUID()
        insertCorruptedRow(partyId, kycStatus = "NOT_A_REAL_STAGE")

        Given {
            contentType("application/json")
        } When {
            get("/api/v1/onboarding/records/$partyId")
        } Then {
            // Not 200 (which before this change silently answered kycStatus: null, i.e. KYC_OPEN)
            // and not 404 (the row is there). IllegalStateException is libs-runtime's convention
            // for "a persisted value is data corruption" and maps to 422.
            statusCode(422)
        }
    }

    @Test
    @TestSecurity(user = "operator", roles = [Roles.OPERATOR])
    fun `a row with a null kyc_status still reads as the open funnel stage`() {
        val partyId = UUID.randomUUID()
        insertCorruptedRow(partyId, kycStatus = null)

        Given {
            contentType("application/json")
        } When {
            get("/api/v1/onboarding/records/$partyId")
        } Then {
            statusCode(200)
            body("kycStatus", org.hamcrest.Matchers.nullValue())
        }
    }

    private fun insertCorruptedRow(partyId: UUID, kycStatus: String?) {
        plantedPartyIds.add(partyId)
        jdbc().use { conn ->
            conn.prepareStatement(INSERT_ROW).use { st ->
                st.setObject(1, partyId)
                st.setString(2, kycStatus)
                val now = java.sql.Timestamp.from(Instant.now())
                st.setTimestamp(3, now)
                st.setTimestamp(4, now)
                st.executeUpdate()
            }
        }
    }

    private fun jdbc(): Connection {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }

    private companion object {
        const val INSERT_ROW =
            "INSERT INTO onboarding_records " +
                "(party_id, party_status, kyc_status, sca_enrolled, device_count, funnel_stage, " +
                "created_at, updated_at) " +
                "VALUES (?, 'ACTIVE', ?, false, 0, 'KYC_OPEN', ?, ?)"
    }
}
