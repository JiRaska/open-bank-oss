// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.it

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * #15939: a rail stores its OWN payment id in `transactionId`, so a reader must know which service
 * opened the case. Through real HTTP and a real Postgres: the opener is recorded from the
 * authenticated machine caller and read back on GET; a staff case records none.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class AmlCaseSourceServiceIT {

    @Inject
    lateinit var dataSource: DataSource

    private fun caseJson(payment: UUID) =
        """{"partyId":"${UUID.randomUUID()}","transactionId":"$payment","customerReference":"REF-1",""" +
            """"screeningType":"TRANSACTION_MONITORING","riskLevel":"LOW","alertCode":"SCREENING_UNAVAILABLE"}"""

    private fun create(payment: UUID): String = Given {
        contentType("application/json")
        header("Idempotency-Key", "aml-$payment-SCREENING_UNAVAILABLE")
        body(caseJson(payment))
    } When {
        post("/api/v1/aml/cases")
    } Then {
        statusCode(201)
    } Extract {
        path("id")
    }

    private fun storedSource(caseId: String): String? = dataSource.connection.use { c ->
        c.prepareStatement("select source_service from aml_cases where case_id = ?").use { ps ->
            ps.setObject(1, UUID.fromString(caseId))
            ps.executeQuery().use { rs ->
                check(rs.next()) { "no row for case $caseId" }
                rs.getString(1)
            }
        }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-domestic-payment", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-domestic-payment"),
            Claim(key = "sub", value = "domestic-service-subject"),
            Claim(key = "preferred_username", value = "service-account-openbank-domestic-payment"),
        ],
    )
    fun `a case opened by the domestic payment service account names that service on GET`() {
        val caseId = create(UUID.randomUUID())
        assertThat(storedSource(caseId)).isEqualTo("openbank-domestic-payment")
        Given { accept("application/json") } When {
            get("/api/v1/aml/cases/$caseId")
        } Then {
            statusCode(200)
            body("sourceService", equalTo("openbank-domestic-payment"))
        }
    }

    @Test
    @TestSecurity(user = "u-operator", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "u-operator"),
            Claim(key = "preferred_username", value = "u-operator"),
        ],
    )
    fun `a staff-created case records no source service`() {
        val caseId = create(UUID.randomUUID())
        assertThat(storedSource(caseId)).isNull()
        Given { accept("application/json") } When {
            get("/api/v1/aml/cases/$caseId")
        } Then {
            statusCode(200)
            body("sourceService", nullValue())
        }
    }
}
