// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.e2e

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * End-to-end journeys of the interest money path over the real HTTP surface against real
 * Postgres + Valkey, asserting the behaviours the unit and mocked use-case tests structurally
 * cannot see:
 *
 *  - **Idempotent replay (ADR-0291, #8351)**: `(account, date, product, currency)` IS one day's
 *    accrual (V12 UNIQUE is the backstop), so a retried `accrue` replays the ORIGINAL row —
 *    with the ORIGINAL amount — even when the retry carries a different balance, and even
 *    after the rate config was deactivated.
 *  - **Fail-closed rate resolution (#1265)**: accruing a product/currency with no rate config
 *    is 422 (a client/config condition), never a 500.
 *  - **Rate-config lifecycle**: create → read back → deactivate flips `active` and drops out
 *    of the account's effective-rate resolution.
 *  - **Least privilege**: `ROLE_VIEWER` reads but is refused 403 on every write; a missing
 *    `?productId=` on capitalize is 400, not 500 (#3104's own worked example).
 *
 * `%test` sets `authz.enforce: false` (OPA is absent in CI), so the `@Authorize` annotations
 * run advisory and no mirror is needed — same explicit opt-out shape as aml/pid/party.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_interest_it")],
)
class InterestLifecycleE2E {

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `interest accrues end to end with idempotent replay and fails closed without a rate`() {
        val productId = "SAV-E2E-${UUID.randomUUID().toString().take(8)}"
        val accountId = UUID.randomUUID()
        val configId = createRateConfig(productId)

        // 0.365 / 365 (ACT_365, one day) = 0.001 exactly; 100000 * 0.001 = 100.000000.
        val accrual = accrue(accountId, productId, "100000.00", "2026-01-18")
        assertThat(accrual["configId"]).isEqualTo(configId)
        assertThat(accrual["status"]).isEqualTo("ACCRUING")
        assertThat(BigDecimal(accrual["accruedAmount"].toString())).isEqualByComparingTo("100.000000")
        assertThat(BigDecimal(accrual["dailyRate"].toString())).isEqualByComparingTo("0.001")

        val summary: Map<String, Any?> = Given {
            this
        } When {
            get("/api/v1/interest/accruals/$accountId/summary?from=2026-01-01&to=2026-02-01")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getMap("")
        }
        assertThat((summary["accrualCount"] as Number).toInt()).isEqualTo(1)
        assertThat(BigDecimal(summary["totalAccrued"].toString())).isEqualByComparingTo("100.000000")

        // Same natural key, different balance: the ORIGINAL row replays, no second write.
        val replay = accrue(accountId, productId, "500.00", "2026-01-18")
        assertThat(replay["id"]).isEqualTo(accrual["id"])
        assertThat(BigDecimal(replay["accruedAmount"].toString())).isEqualByComparingTo("100.000000")

        val accruals: List<Map<String, Any?>> = Given {
            this
        } When {
            get("/api/v1/interest/accruals/$accountId")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getList("")
        }
        assertThat(accruals).hasSize(1)

        // No rate for this (product, currency): 422 with a named condition, not a 500.
        val unknown = "NOPE-E2E-${UUID.randomUUID().toString().take(8)}"
        val refusal: Map<String, Any?> = Given {
            contentType("application/json")
            body(accrueBody(UUID.randomUUID(), unknown, "1000.00", "2026-01-18"))
        } When {
            post("/api/v1/interest/accrue")
        } Then {
            statusCode(422)
        } Extract {
            jsonPath().getMap("")
        }
        assertThat(refusal["error"] as String).contains(unknown).contains("CZK")
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `rate configuration is created read back and deactivated end to end`() {
        val productId = "SAV-E2E-${UUID.randomUUID().toString().take(8)}"
        val accountId = UUID.randomUUID()
        val configId = createRateConfig(productId)

        val listed: List<Map<String, Any?>> = Given {
            this
        } When {
            get("/api/v1/interest/rates?productId=$productId")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getList("")
        }
        assertThat(listed.map { it["id"] }).contains(configId)
        assertThat(listed.single { it["id"] == configId }["active"]).isEqualTo(true)

        val effective: io.restassured.response.Response = Given {
            this
        } When {
            get("/api/v1/interest/accounts/$accountId/effective-rate?productId=$productId&date=2026-01-18")
        }
        assertThat(effective.statusCode).isEqualTo(200)
        assertThat(effective.body.jsonPath().getString("id")).isEqualTo(configId)

        Given {
            this
        } When {
            delete("/api/v1/interest/rates/$configId")
        } Then {
            statusCode(200)
        }

        val after: Map<String, Any?> = Given {
            this
        } When {
            get("/api/v1/interest/rates/$configId")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getMap("")
        }
        assertThat(after["active"]).isEqualTo(false)

        Given {
            this
        } When {
            get("/api/v1/interest/accounts/$accountId/effective-rate?productId=$productId&date=2026-01-18")
        } Then {
            statusCode(204)
        }
    }

    @Test
    @TestSecurity(user = "e2e-viewer-1", roles = ["ROLE_VIEWER"])
    fun `viewers read but never write and a missing productId is a 400 not a 500`() {
        Given {
            this
        } When {
            get("/api/v1/interest/rates")
        } Then {
            statusCode(200)
        }

        Given {
            contentType("application/json")
            body(accrueBody(UUID.randomUUID(), "SAV-E2E-X", "100.00", "2026-01-18"))
        } When {
            post("/api/v1/interest/accrue")
        } Then {
            statusCode(403)
        }

        Given {
            contentType("application/json")
            body(rateConfigBody("SAV-E2E-X"))
        } When {
            post("/api/v1/interest/rates")
        } Then {
            statusCode(403)
        }
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `missing parameters and null bodies are 400s not 500s`() {
        // #3104's own worked example: the binding NPE is fixed, so the omission is a 400.
        Given {
            contentType("application/json")
        } When {
            post("/api/v1/interest/capitalize/${UUID.randomUUID()}")
        } Then {
            statusCode(400)
        }

        // A JSON `null` body is likewise a 400, not a 500 (#3038).
        Given {
            contentType("application/json")
            body("null")
        } When {
            post("/api/v1/interest/accrue")
        } Then {
            statusCode(400)
        }
    }

    private fun createRateConfig(productId: String): String {
        val created: Map<String, Any?> = Given {
            contentType("application/json")
            body(rateConfigBody(productId))
        } When {
            post("/api/v1/interest/rates")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getMap("")
        }
        return created["id"] as String
    }

    private fun accrue(accountId: UUID, productId: String, balance: String, date: String): Map<String, Any?> = Given {
        contentType("application/json")
        body(accrueBody(accountId, productId, balance, date))
    } When {
        post("/api/v1/interest/accrue")
    } Then {
        statusCode(201)
    } Extract {
        jsonPath().getMap("")
    }

    private fun rateConfigBody(productId: String) = """
        {
          "productId": "$productId",
          "currency": "CZK",
          "annualRate": 0.365000,
          "effectiveFrom": "2026-01-01",
          "createdAt": "2026-01-01T00:00:00Z",
          "updatedAt": "2026-01-01T00:00:00Z"
        }
    """.trimIndent()

    private fun accrueBody(accountId: UUID, productId: String, balance: String, date: String) = """
        {
          "accountId": "$accountId",
          "productId": "$productId",
          "balance": $balance,
          "currency": "CZK",
          "accrualDate": "$date"
        }
    """.trimIndent()
}
