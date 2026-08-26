// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.filter.log.ResponseLoggingFilter
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(com.openbank.account.it.PostgresRedpandaRedisTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AccountApiIT {

    companion object {
        private val partyId = UUID.fromString("00000000-1111-0000-0000-000000000001")
        private val productId = UUID.fromString("00000000-2222-0000-0000-000000000001")
        private val eurProductId = UUID.fromString("00000000-2222-0000-0000-000000000002")
        private var createdAccountId: String? = null

        init {
            RestAssured.filters(ResponseLoggingFilter())
        }
    }

    @Test
    @Order(1)
    fun `GET info returns service metadata`() {
        val body = (Given { this } When { get("/api/v1/info") } Then { statusCode(200) }).extract().body().asString()
        assertThat(body).contains("openbank-account-service")
    }

    @Test
    @Order(2)
    fun `GET health ready returns UP`() {
        Given { this } When { get("/q/health/ready") } Then { statusCode(200) }
    }

    @Test
    @Order(3)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `POST accounts opens a new account and returns 201`() {
        val idempotencyKey = UUID.randomUUID().toString()
        val payload = """
            {
              "partyId": "$partyId",
              "productId": "$productId",
              "accountType": "CURRENT",
              "currencyCode": "CZK",
              "legalName": "Test Customer"
            }
        """.trimIndent()

        val response = Given {
            contentType("application/json")
            header("Idempotency-Key", idempotencyKey)
            body(payload)
        } When {
            post("/api/v1/accounts")
        } Then {
            statusCode(201)
            body("id", notNullValue())
            body("accountType", equalTo("CURRENT"))
            body("currencyCode", equalTo("CZK"))
            body("status", equalTo("ACTIVE"))
        }

        createdAccountId = response.extract().body().jsonPath().getString("id")
        assertThat(createdAccountId).isNotNull
    }

    @Test
    @Order(4)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `POST accounts with same idempotency key replays response`() {
        val idempotencyKey = UUID.randomUUID().toString()
        val payload = """
            {
              "partyId": "$partyId",
              "productId": "$eurProductId",
              "accountType": "SAVINGS",
              "currencyCode": "EUR",
              "legalName": "Test Customer"
            }
        """.trimIndent()

        Given {
            contentType("application/json")
            header("Idempotency-Key", idempotencyKey)
            body(payload)
        } When { post("/api/v1/accounts") } Then { statusCode(201) }

        Given {
            contentType("application/json")
            header("Idempotency-Key", idempotencyKey)
            body(payload)
        } When {
            post("/api/v1/accounts")
        } Then {
            statusCode(201)
            header("X-Idempotency-Replayed", equalTo("true"))
        }
    }

    @Test
    @Order(5)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `POST accounts persists a term deposit account`() {
        val payload = """
            {
              "partyId": "$partyId",
              "productId": "00000000-2222-0000-0000-000000000002",
              "accountType": "TERM_DEPOSIT",
              "currencyCode": "EUR",
              "legalName": "Test Customer"
            }
        """.trimIndent()

        Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(payload)
        } When {
            post("/api/v1/accounts")
        } Then {
            statusCode(201)
            body("accountType", equalTo("TERM_DEPOSIT"))
            body("currencyCode", equalTo("EUR"))
            body("status", equalTo("ACTIVE"))
        }
    }

    @Test
    @Order(5)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `GET accounts by partyId returns list`() {
        val body = (
            Given {
                queryParam("partyId", partyId.toString())
            } When {
                get("/api/v1/accounts")
            } Then {
                statusCode(200)
                body("data", notNullValue())
            }
            ).extract().body().asString()
        assertThat(body).contains("CZK")
    }

    @Test
    @Order(6)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `GET account by id returns account`() {
        val id = createdAccountId ?: return
        Given { this } When {
            get("/api/v1/accounts/$id")
        } Then {
            statusCode(200)
            body("id", equalTo(id))
            body("accountType", equalTo("CURRENT"))
        }
    }

    @Test
    @Order(7)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `GET account by id enforces X-Customer-Party-Id ownership (IDOR defense-in-depth, A1)`() {
        val id = createdAccountId ?: return
        // Customer-scoped call whose party owns the account → 200.
        Given { header("X-Customer-Party-Id", partyId.toString()) } When {
            get("/api/v1/accounts/$id")
        } Then { statusCode(200) }
        // Customer-scoped call for a DIFFERENT party → 404 (not 403; no existence oracle), even though
        // the row exists. An operator call with no header still reads it (the Order-6 test).
        Given { header("X-Customer-Party-Id", UUID.randomUUID().toString()) } When {
            get("/api/v1/accounts/$id")
        } Then { statusCode(404) }
    }

    // Balance is owned by the balance-service (N3 / ADR-0024); the account-service balance endpoint
    // now delegates over REST and is covered by the balance-service's own tests, not here.

    @Test
    @Order(8)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `GET account by unknown id returns 404`() {
        Given { this } When {
            get("/api/v1/accounts/${UUID.randomUUID()}")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @Order(9)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `GET accounts without partyId returns 400`() {
        Given { this } When { get("/api/v1/accounts") } Then { statusCode(400) }
    }

    @Test
    @Order(10)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `POST freeze account returns frozen status`() {
        val id = createdAccountId ?: return
        Given {
            contentType("application/json")
            body("""{"reason": "Suspicious activity"}""")
        } When {
            post("/api/v1/accounts/$id/freeze")
        } Then {
            statusCode(200)
            body("status", equalTo("FROZEN"))
        }
    }

    @Test
    @Order(11)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `POST unfreeze account returns active status`() {
        val id = createdAccountId ?: return
        Given {
            contentType("application/json")
            body("""{"reason": "Review completed"}""")
        } When {
            post("/api/v1/accounts/$id/unfreeze")
        } Then {
            statusCode(200)
            body("status", equalTo("ACTIVE"))
        }
    }

    @Test
    @Order(12)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `POST close account returns closed status`() {
        val id = createdAccountId ?: return
        Given {
            contentType("application/json")
            body("""{"reason": "Customer request"}""")
        } When {
            post("/api/v1/accounts/$id/close")
        } Then {
            statusCode(200)
            body("status", equalTo("CLOSED"))
        }
    }
}
