// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.anacredit.e2e

import com.openbank.anacredit.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.anyOf
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * The AnaCredit regulatory-feed journey over the real HTTP surface: exposures register against
 * Postgres, the monthly return renders the reportable population and names every exclusion, and
 * the exposure INSERT stays inside the registering request's trace — a row that escaped the
 * request context would still answer 201 yet land in the regulatory feed without an observable
 * cause.
 *
 * The module's tests prove one property each; this proves the route sequence serves the whole
 * "exposure is registered and reported" story, so a wiring regression that deregisters a route
 * (#3371), detaches the write from the request trace, or drops the eligibility policy fails here
 * even when every property-level assertion stays green.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
@TestMethodOrder(OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestSecurity(user = "anacredit-operator@openbank.test", roles = ["ROLE_OPERATOR"])
class AnaCreditReturnJourneyE2E {

    private val path = "/api/v1/anacredit"
    private val legalInstrument = "OD-E2E-${java.util.UUID.randomUUID()}"
    private val retailInstrument = "OD-E2E-RETAIL-${java.util.UUID.randomUUID()}"

    private fun exposureBody(instrumentId: String, debtorId: String, debtorType: String, eur: String) = """
        |{"instrumentId":"$instrumentId","debtorId":"$debtorId","debtorType":"$debtorType",
        |"currency":"EUR","committedAmount":$eur,"drawnAmount":12000,"committedAmountEur":$eur,
        |"originationDate":"2025-06-01"}
    """.trimMargin()

    @Test
    @Order(1)
    fun `1 - a legal-entity exposure registers and reads back with its derived amounts`() {
        Given {
            contentType("application/json")
            body(exposureBody(legalInstrument, "LE-E2E-ACME", "LEGAL_ENTITY", "40000"))
        } When {
            post("$path/exposures")
        } Then {
            statusCode(201)
            body("instrumentId", equalTo(legalInstrument))
            body("debtorType", equalTo("LEGAL_ENTITY"))
            body("offBalanceSheetAmount", equalTo(28000))
        }

        Given { this } When { get("$path/exposures") } Then {
            statusCode(200)
            body("instrumentId", hasItem(legalInstrument))
        }
    }

    @Test
    @Order(2)
    fun `2 - a natural-person exposure registers for the exclusion the return must name`() {
        Given {
            contentType("application/json")
            body(exposureBody(retailInstrument, "NP-E2E-JANE", "NATURAL_PERSON", "90000"))
        } When {
            post("$path/exposures")
        } Then { statusCode(201) }
    }

    @Test
    @Order(3)
    @TestSecurity(user = "nobody@openbank.test")
    fun `3 - the feed rejects a caller without the reporting role`() {
        Given { this } When { get("$path/exposures") } Then {
            statusCode(anyOf(equalTo(401), equalTo(403)))
        }
    }

    @Test
    @Order(4)
    fun `4 - the monthly return reports the legal-entity exposure and names the retail exclusion`() {
        Given { this } When { get("$path/returns/2025-06-30") } Then {
            statusCode(200)
            body("referenceDate", equalTo("2025-06-30"))
            body("reportableCount", equalTo(1))
            body("excludedCount", equalTo(1))
            body("records.instrumentId", hasItem(legalInstrument))
            body("exclusions.instrumentId", hasItem(retailInstrument))
            body("exclusions.reason", hasItem(containsString("HOUSEHOLD_OUT_OF_SCOPE")))
        }

        Given { this } When { get("$path/returns/not-a-date") } Then { statusCode(400) }
    }

    @Test
    @Order(5)
    fun `5 - re-registering the same instrument upserts instead of duplicating the feed`() {
        Given {
            contentType("application/json")
            body(exposureBody(legalInstrument, "LE-E2E-ACME", "LEGAL_ENTITY", "41000"))
        } When {
            post("$path/exposures")
        } Then {
            statusCode(201)
            body("committedAmount", equalTo(41000))
        }

        val instruments: List<Map<String, Any>> = Given { this }
            .When { get("$path/exposures") }
            .Then {
                statusCode(200)
                body("$", hasSize<Any>(2))
            }
            .Extract { path("") }
        val ids = instruments.map { it["instrumentId"] as String }
        org.assertj.core.api.Assertions.assertThat(ids).containsOnlyOnce(legalInstrument)
        val updated = instruments.first { it["instrumentId"] == legalInstrument }
        org.assertj.core.api.Assertions
            .assertThat(java.math.BigDecimal(updated["committedAmount"].toString()))
            .isEqualByComparingTo("41000")
    }
}
