// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

@QuarkusTest
@QuarkusTestResource(com.openbank.fraud.it.PostgresRedisTestResource::class)
class FraudApiIT {

    @Test
    fun `GET info returns service metadata`() {
        val body = (Given { this } When { get("/api/v1/info") } Then { statusCode(200) }).extract().body().asString()
        assertThat(body).contains("openbank-fraud-service")
    }

    @Test
    fun `GET health ready returns UP`() {
        Given { this } When { get("/q/health/ready") } Then { statusCode(200) }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `POST score returns the baseline ALLOW verdict`() {
        val payload = """
            {
              "amount": "1250.00",
              "currency": "CZK",
              "rail": "SEPA_INSTANT",
              "accountId": "00000000-0000-0000-0000-0000000000a1",
              "counterpartyId": "00000000-0000-0000-0000-0000000000b2"
            }
        """.trimIndent()

        Given {
            contentType("application/json")
            body(payload)
        } When {
            post("/api/v1/fraud/score")
        } Then {
            statusCode(200)
            body("verdict", equalTo("ALLOW"))
            body("ruleVersion", equalTo("v4"))
            body("score", equalTo(0))
        }
    }

    @Test
    fun `POST score without a role is rejected`() {
        Given {
            contentType("application/json")
            body("""{"amount":"10.00","currency":"CZK","rail":"SEPA"}""")
        } When {
            post("/api/v1/fraud/score")
        } Then {
            statusCode(401)
        }
    }
}
