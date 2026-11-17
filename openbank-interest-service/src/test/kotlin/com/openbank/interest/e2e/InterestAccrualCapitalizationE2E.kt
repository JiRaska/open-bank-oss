// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.e2e

import com.openbank.interest.integration.LedgerBoundary
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

/**
 * The interest money path over the real HTTP surface: an operator publishes a rate config,
 * accrues one day of interest against it (idempotent on the natural key), reads the accrual
 * back through the collection routes, capitalizes the period — gross, withholding tax and net
 * computed by the real policy from the fail-safe default tax profile — and observes the
 * capitalization and the accrual's state flip through their own read routes.
 *
 * Ordered, because the lifecycle is a state machine — each step is only reachable from the
 * previous one.
 *
 * The integration tests in this module prove one property each (transaction atomicity, ledger
 * journal line equality, claim recovery, tax-profile freezing); this proves the operator-visible
 * route sequence serves the whole accrue → capitalize → read-back story over HTTP, so a wiring
 * regression that deregisters a route (#3371), breaks the idempotent replay, or stops flipping
 * accrual state fails here even when every property-level assertion stays green.
 *
 * The only boundary standing in for another service is the ledger posting port, through the
 * module's established `LedgerBoundary` fake (it books the real journal lines via the production
 * `CapitalizationJournalFactory` and never fabricates a receipt id); everything else — rate
 * config, accrual evaluation, withholding policy, persistence, state machine — is the real,
 * CDI-wired code. The tax profile resolves through the service's own fail-safe default provider
 * (no downstream call).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_interest_e2e")],
)
@TestProfile(InterestAccrualCapitalizationE2E.JourneyProfile::class)
@TestSecurity(user = "interest-operator", roles = ["ROLE_OPERATOR"])
class InterestAccrualCapitalizationE2E {

    /** Own boot (own container DB and a fresh LedgerBoundary), mirroring the ITs' isolation. */
    class JourneyProfile : QuarkusTestProfile

    @Inject
    lateinit var ledger: LedgerBoundary

    private val accountId = UUID.randomUUID()
    private val otherAccountId = UUID.randomUUID()

    private var rateConfigId: String? = null
    private var accrualId: String? = null
    private var capitalizationId: String? = null

    @Test
    @Order(1)
    fun `1 - the operator publishes a rate config and an identical retry replays it`() {
        val body = """
            {
              "productId": "$PRODUCT",
              "currency": "CZK",
              "annualRate": 0.365000,
              "effectiveFrom": "2026-01-01",
              "createdAt": "2026-01-01T00:00:00Z",
              "updatedAt": "2026-01-01T00:00:00Z"
            }
        """.trimIndent()

        rateConfigId = Given {
            contentType("application/json")
            body(body)
        } When {
            post("/api/v1/interest/rates")
        } Then {
            statusCode(201)
            body("id", notNullValue())
            body("productId", equalTo(PRODUCT))
            body("active", equalTo(true))
        } Extract { path("id") }

        // Idempotent on the natural key (productId, accountId, currency, effectiveFrom):
        // an identical retry replays the already-active config instead of shadowing it.
        Given {
            contentType("application/json")
            body(body)
        } When {
            post("/api/v1/interest/rates")
        } Then {
            statusCode(201)
            body("id", equalTo(rateConfigId))
        }
    }

    @Test
    @Order(2)
    fun `2 - one day of interest accrues at the published rate`() {
        // ACT_365: 100000 × 0.365 / 365 = exactly 100.00 for one day.
        accrualId = Given {
            contentType("application/json")
            body(
                """
                {
                  "accountId": "$accountId",
                  "productId": "$PRODUCT",
                  "balance": 100000,
                  "currency": "CZK",
                  "accrualDate": "2026-06-15"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/interest/accrue")
        } Then {
            statusCode(201)
            body("accountId", equalTo(accountId.toString()))
            body("productId", equalTo(PRODUCT))
            body("accrualDate", equalTo("2026-06-15"))
            body("accruedAmount", equalTo(100.0f))
            body("currency", equalTo("CZK"))
            body("status", equalTo("ACCRUING"))
        } Extract { path("id") }
    }

    @Test
    @Order(3)
    fun `3 - the accrual reads back through the collection routes`() {
        Given { this } When { get("/api/v1/interest/accruals/$accountId") } Then {
            statusCode(200)
            body("", hasSize<Any>(1))
            body("[0].id", equalTo(accrualId))
            body("[0].accruedAmount", equalTo(100.0f))
            body("[0].status", equalTo("ACCRUING"))
        }

        Given { this } When { get("/api/v1/interest/accruals") } Then {
            statusCode(200)
            body("find { it.id == '$accrualId' }.accountId", equalTo(accountId.toString()))
        }
    }

    @Test
    @Order(4)
    fun `4 - a retry with the same natural key replays the original accrual, no second write`() {
        Given {
            contentType("application/json")
            body(
                """
                {
                  "accountId": "$accountId",
                  "productId": "$PRODUCT",
                  "balance": 100000,
                  "currency": "CZK",
                  "accrualDate": "2026-06-15"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/interest/accrue")
        } Then {
            statusCode(201)
            body("id", equalTo(accrualId))
        }

        // The replay wrote nothing: the collection still holds exactly one row.
        Given { this } When { get("/api/v1/interest/accruals/$accountId") } Then {
            statusCode(200)
            body("", hasSize<Any>(1))
        }
    }

    @Test
    @Order(5)
    fun `5 - an account with no rate config accrues nothing, loudly`() {
        Given {
            contentType("application/json")
            body(
                """
                {
                  "accountId": "$otherAccountId",
                  "productId": "NO_SUCH_PRODUCT_E2E",
                  "balance": 100000,
                  "currency": "CZK",
                  "accrualDate": "2026-06-15"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/interest/accrue")
        } Then {
            // No active rate config for the (account/product, currency) — a client/config
            // condition, never a 500 (issue #1265).
            statusCode(422)
        }
    }

    @Test
    @Order(6)
    fun `6 - capitalizing the period splits gross, withholding tax and net`() {
        capitalizationId = Given {
            contentType("application/json")
        } When {
            post("/api/v1/interest/capitalize/$accountId?productId=$PRODUCT&toDate=2026-06-15")
        } Then {
            statusCode(200)
            body("accountId", equalTo(accountId.toString()))
            body("productId", equalTo(PRODUCT))
            body("grossAmount", equalTo(100.0f))
            // Fail-safe default profile: CZ-resident individual, 15% withholding on whole CZK.
            body("taxAmount", equalTo(15))
            body("netAmount", equalTo(85.0f))
        } Extract { path("id") }

        // The journal reached the ledger boundary exactly once, for this account and period —
        // booked through the production journal factory, so the legs are the real Money lines.
        assertThat(ledger.booked()).hasSize(1)
    }

    @Test
    @Order(7)
    fun `7 - a second capitalize after completion is refused, never a second journal`() {
        // A completed period is not re-opened: with every accrual CAPITALIZED there is no pending
        // set, and the service fails closed rather than silently re-booking. (A retry that
        // recovers an *interrupted* attempt is the claim-recovery path, covered by the ITs.)
        Given {
            // The route @Consumes JSON: without an explicit content type the retry would be
            // rejected 415 before the business rule is even reached.
            contentType("application/json")
        } When {
            post("/api/v1/interest/capitalize/$accountId?productId=$PRODUCT&toDate=2026-06-15")
        } Then {
            statusCode(422)
            body("code", equalTo("BUSINESS_RULE_VIOLATION"))
            body("message", containsString("No pending accruals to capitalize"))
        }

        // Exactly-once on money (ADR-0033): the refusal posted nothing — the ledger boundary
        // still holds exactly the one journal from test 6.
        assertThat(ledger.booked()).hasSize(1)
    }

    @Test
    @Order(8)
    fun `8 - the capitalization and the flipped accrual read back through their own routes`() {
        Given { this } When { get("/api/v1/interest/capitalizations/$accountId") } Then {
            statusCode(200)
            body("find { it.id == '$capitalizationId' }.grossAmount", equalTo(100.0f))
            body("find { it.id == '$capitalizationId' }.taxAmount", equalTo(15.0f))
            body("find { it.id == '$capitalizationId' }.netAmount", equalTo(85.0f))
        }

        // The claimed accrual flipped — the read route, not the capitalize response, is the
        // observable.
        Given { this } When { get("/api/v1/interest/accruals/$accountId") } Then {
            statusCode(200)
            body("[0].status", equalTo("CAPITALIZED"))
        }
    }

    @Test
    @Order(9)
    fun `9 - the journey fails closed on a missing body`() {
        // A JSON null body deserialises to null and must be a 400, not an NPE 500 (#3038).
        Given {
            contentType("application/json")
            body("null")
        } When {
            post("/api/v1/interest/accrue")
        } Then {
            statusCode(400)
        }
    }

    private companion object {
        const val PRODUCT = "SAVINGS_E2E"
    }
}
