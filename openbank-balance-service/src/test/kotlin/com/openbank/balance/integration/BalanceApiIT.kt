// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.response.ExtractableResponse
import io.restassured.response.Response
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.math.BigDecimal
import java.util.UUID

/**
 * End-to-end HTTP integration test for the balance lifecycle (money-path, ADR-0024 multi-currency
 * pockets): initialize per currency, read single pocket and all pockets, reserve/release holds, and
 * the K7 access-control behavior (401 unauthenticated, viewers cannot move money, overdraft-limit
 * override is supervisor-only). Runs against a real Postgres with Flyway V1..V8 applied and a real
 * Redpanda broker (per-job Testcontainers, #578). No downstream service is called: the ledger REST
 * client is only used by reconciliation (not exercised here) and the scheduler is disabled in %test.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.balance.it.PostgresRedpandaTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class BalanceApiIT {

    companion object {
        private val accountId: UUID = UUID.randomUUID()
        private var holdId: String? = null

        private fun amount(response: ExtractableResponse<Response>, path: String): BigDecimal =
            BigDecimal(response.body().jsonPath().getString(path))
    }

    @Test
    @Order(1)
    fun `GET info returns service metadata`() {
        val body = (Given { this } When { get("/api/v1/info") } Then { statusCode(200) }).extract().body().asString()
        assertThat(body).contains("openbank-balance-service")
    }

    @Test
    @Order(2)
    fun `GET health ready returns UP`() {
        Given { this } When { get("/q/health/ready") } Then { statusCode(200) }
    }

    @Test
    @Order(3)
    fun `GET balances without identity returns 401 (K7 - no anonymous read on a money service)`() {
        Given { this } When { get("/api/v1/balances/$accountId") } Then { statusCode(401) }
    }

    @Test
    @Order(4)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `POST initialize creates a CZK pocket with opening balance`() {
        Given {
            contentType("application/json")
            body("""{"currency": "CZK", "initialAmount": "1000.00"}""")
        } When {
            post("/api/v1/balances/$accountId/initialize")
        } Then {
            statusCode(201)
            body("id", notNullValue())
            body("accountId", equalTo(accountId.toString()))
            body("currency", equalTo("CZK"))
        }
    }

    @Test
    @Order(5)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `POST initialize is idempotent - replay returns the existing pocket unchanged`() {
        // Second initialize for the same (account, currency) must NOT reset the balance.
        val replay = (
            Given {
                contentType("application/json")
                body("""{"currency": "CZK", "initialAmount": "9999.00"}""")
            } When {
                post("/api/v1/balances/$accountId/initialize")
            } Then {
                statusCode(201)
            }
            ).extract()

        assertThat(amount(replay, "bookedAmount")).isEqualByComparingTo(BigDecimal("1000.00"))
        assertThat(amount(replay, "availableAmount")).isEqualByComparingTo(BigDecimal("1000.00"))
    }

    @Test
    @Order(6)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `POST initialize creates a second currency pocket (EUR) on the same account`() {
        Given {
            contentType("application/json")
            body("""{"currency": "EUR"}""")
        } When {
            post("/api/v1/balances/$accountId/initialize")
        } Then {
            statusCode(201)
            body("currency", equalTo("EUR"))
        }
    }

    @Test
    @Order(7)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `GET balances returns all currency pockets of the account`() {
        val response = (
            Given { this } When {
                get("/api/v1/balances/$accountId")
            } Then {
                statusCode(200)
                body("balances.size()", equalTo(2))
            }
            ).extract()

        val currencies = response.body().jsonPath().getList<String>("balances.currency")
        assertThat(currencies).containsExactlyInAnyOrder("CZK", "EUR")
    }

    @Test
    @Order(8)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `GET balance per currency returns the CZK pocket`() {
        val response = (
            Given { this } When {
                get("/api/v1/balances/$accountId/CZK")
            } Then {
                statusCode(200)
                body("currency", equalTo("CZK"))
            }
            ).extract()

        assertThat(amount(response, "bookedAmount")).isEqualByComparingTo(BigDecimal("1000.00"))
        assertThat(amount(response, "availableAmount")).isEqualByComparingTo(BigDecimal("1000.00"))
    }

    @Test
    @Order(9)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `GET balance for unknown account returns 404`() {
        Given { this } When {
            get("/api/v1/balances/${UUID.randomUUID()}/CZK")
        } Then {
            statusCode(404)
            body("error", equalTo("NOT_FOUND"))
        }
    }

    @Test
    @Order(10)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `POST credit as viewer is forbidden (K7 - viewers must not move money)`() {
        Given {
            contentType("application/json")
            body("""{"amount": "10.00", "currency": "CZK", "referenceId": "viewer-must-not-credit"}""")
        } When {
            post("/api/v1/balances/$accountId/credit")
        } Then {
            statusCode(403)
        }
    }

    @Test
    @Order(11)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `POST hold reserves funds and reduces available, not booked`() {
        val response = (
            Given {
                contentType("application/json")
                body(
                    """{"amount": "400.00", "currency": "CZK", "reason": "card authorization", "referenceId": "hold-ref-1"}""",
                )
            } When {
                post("/api/v1/balances/$accountId/holds")
            } Then {
                statusCode(201)
                body("id", notNullValue())
                body("currency", equalTo("CZK"))
            }
            ).extract()

        holdId = response.body().jsonPath().getString("id")

        val balance = (
            Given { this } When {
                get("/api/v1/balances/$accountId/CZK")
            } Then { statusCode(200) }
            ).extract()
        assertThat(amount(balance, "bookedAmount")).isEqualByComparingTo(BigDecimal("1000.00"))
        assertThat(amount(balance, "availableAmount")).isEqualByComparingTo(BigDecimal("600.00"))
        assertThat(amount(balance, "reservedAmount")).isEqualByComparingTo(BigDecimal("400.00"))
    }

    @Test
    @Order(12)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `POST hold beyond available funds returns 422 INSUFFICIENT_FUNDS`() {
        Given {
            contentType("application/json")
            body("""{"amount": "5000.00", "currency": "CZK", "reason": "too large", "referenceId": "hold-ref-2"}""")
        } When {
            post("/api/v1/balances/$accountId/holds")
        } Then {
            statusCode(422)
            body("error", equalTo("INSUFFICIENT_FUNDS"))
        }
    }

    @Test
    @Order(13)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `DELETE hold releases the reservation and restores available`() {
        val id = holdId ?: return
        Given { this } When {
            delete("/api/v1/balances/holds/$id")
        } Then {
            statusCode(200)
            body("releasedAt", notNullValue())
        }

        val balance = (
            Given { this } When {
                get("/api/v1/balances/$accountId/CZK")
            } Then { statusCode(200) }
            ).extract()
        assertThat(amount(balance, "availableAmount")).isEqualByComparingTo(BigDecimal("1000.00"))
        assertThat(amount(balance, "reservedAmount")).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    @Order(14)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `DELETE unknown hold returns 404`() {
        Given { this } When {
            delete("/api/v1/balances/holds/${UUID.randomUUID()}")
        } Then {
            statusCode(404)
            body("error", equalTo("NOT_FOUND"))
        }
    }

    @Test
    @Order(15)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `PUT overdraft-limit as operator is forbidden (supervisor-only override)`() {
        Given {
            contentType("application/json")
            body("""{"arrangedOverdraftLimit": "500.00"}""")
        } When {
            put("/api/v1/balances/$accountId/CZK/overdraft-limit")
        } Then {
            statusCode(403)
        }
    }

    @Test
    @Order(16)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_SUPERVISOR"])
    fun `PUT overdraft-limit as supervisor arranges the overdraft`() {
        val response = (
            Given {
                contentType("application/json")
                body("""{"arrangedOverdraftLimit": "500.00"}""")
            } When {
                put("/api/v1/balances/$accountId/CZK/overdraft-limit")
            } Then {
                statusCode(200)
            }
            ).extract()

        assertThat(amount(response, "arrangedOverdraftLimit")).isEqualByComparingTo(BigDecimal("500.00"))
    }
}
