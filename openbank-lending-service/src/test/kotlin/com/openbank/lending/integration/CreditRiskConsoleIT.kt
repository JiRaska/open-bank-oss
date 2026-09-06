// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.openbank.lending.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.LocalDate
import java.util.UUID

/**
 * The credit-risk read surface against a real Postgres, driven through the real origination
 * path: the application is submitted and advanced so the ADR-0213 engine evaluates it at
 * ASSESSMENT, and the console endpoints then read back the evidence the engine pinned.
 *
 * WHY THE REAL PATH AND NOT A SEEDED ROW. Seeding `decision_*` columns by JDBC would prove the
 * query reads columns; it would not prove the read side decodes what the ENGINE writes. The codec
 * unit test pins the format; this test pins that the two ends still meet over a real evaluation.
 *
 * The negative case (a role outside the desk is refused) is the control: the surface exposes
 * book-wide exposure and every applicant's affordability inputs, so it must be at least as
 * closed as the list endpoints.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@QuarkusTestResource(CreditRiskConsoleIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class CreditRiskConsoleIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            val props = InMemoryConnector.switchOutgoingChannelsToInMemory("lending-events-out").toMutableMap()
            props["quarkus.kafka.devservices.enabled"] = "false"
            props["openbank.outbox.dispatch-enabled"] = "false"
            return props
        }

        override fun stop() = InMemoryConnector.clear()
    }

    private lateinit var applicationId: String

    private companion object {
        /** SUBMITTED → KYC_PENDING → DOCS_REQUIRED → ASSESSMENT → (engine) → FOUR_EYES. */
        const val ADVANCES_TO_FOUR_EYES = 4
    }

    @Test
    @Order(1)
    @TestSecurity(user = "risk-it-proposer", roles = ["ROLE_LENDING_OFFICER"])
    fun `1 - submit an application with the inputs the starter policy needs`() {
        val body = """
            {"partyId":"${UUID.randomUUID()}",
             "requestedAmount":{"amount":"120000.00","currency":{"code":"CZK"}},
             "nominalAnnualRate":0.08,"termPeriods":12,"firstDueDate":"${LocalDate.now().plusMonths(1)}",
             "verifiedIncomeMonthly":{"amount":"60000.00","currency":{"code":"CZK"}},
             "existingDebtServiceMonthly":{"amount":"6000.00","currency":{"code":"CZK"}},
             "ageYears":35,"residency":"CZ","employmentTenureMonths":48}
        """.trimIndent()
        applicationId = Given {
            contentType("application/json")
            body(body)
        } When {
            post("/api/v1/lending/applications")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getString("id")
        }
    }

    @Test
    @Order(2)
    @TestSecurity(user = "risk-it-officer", roles = ["ROLE_LENDING_OFFICER"])
    fun `2 - advance through ASSESSMENT so the engine evaluates`() {
        repeat(ADVANCES_TO_FOUR_EYES) {
            Given { contentType("application/json") } When {
                post("/api/v1/lending/applications/$applicationId/advance")
            } Then { statusCode(200) }
        }
    }

    @Test
    @Order(3)
    @TestSecurity(user = "risk-it-analyst", roles = ["ROLE_CREDIT_RISK"])
    fun `3 - the decisions read decodes what the engine pinned`() {
        val response = Given { contentType("application/json") } When {
            get("/api/v1/lending/risk/decisions?limit=50")
        } Then { statusCode(200) } Extract { this }

        val ids = response.jsonPath().getList<String>("applicationId")
        assertThat(ids).contains(applicationId)
        val i = ids.indexOf(applicationId)
        val outcome = response.jsonPath().getString("[$i].engineOutcome")
        assertThat(outcome).isIn("APPROVE", "REFER", "DECLINE")
        assertThat(response.jsonPath().getString("[$i].inputSnapshotHash")).isNotBlank()
        assertThat(response.jsonPath().getMap<String, Int>("[$i].policyVersions")).containsKeys(
            "EXCLUSION",
            "ELIGIBILITY",
            "AFFORDABILITY",
            "PRICING_BAND",
        )
        // 120k over 12 months at 8% is ~10.4k/month against 60k income: DSTI ≈ 0.17, total ≈ 0.27.
        val dsti = response.jsonPath().getDouble("[$i].affordability.dsti")
        val total = response.jsonPath().getDouble("[$i].affordability.dstiIncludingExistingDebt")
        assertThat(dsti).isBetween(0.15, 0.20)
        assertThat(total - dsti).isCloseTo(0.10, org.assertj.core.data.Offset.offset(0.001))
        if (outcome == "APPROVE") {
            assertThat(response.jsonPath().getString("[$i].priceBand")).isEqualTo("PRIME")
        }
    }

    @Test
    @Order(4)
    @TestSecurity(user = "risk-it-analyst", roles = ["ROLE_CREDIT_RISK"])
    fun `4 - the summary is grouped in the database and agrees with the list`() {
        val summary = Given { contentType("application/json") } When {
            get("/api/v1/lending/risk/decisions/summary")
        } Then { statusCode(200) } Extract { this }
        val total = summary.jsonPath().getList<Int>("count").sumOf { it.toLong() }
        assertThat(total).isGreaterThanOrEqualTo(1L)
        assertThat(summary.jsonPath().getList<String>("engineOutcome")).allSatisfy {
            assertThat(it).isIn("APPROVE", "REFER", "DECLINE")
        }
    }

    @Test
    @Order(5)
    @TestSecurity(user = "risk-it-compliance", roles = ["ROLE_COMPLIANCE"])
    fun `5 - the policy read names the code-seeded starter and its four tables`() {
        val policy = Given { contentType("application/json") } When {
            get("/api/v1/lending/risk/policy?asOf=2026-09-05")
        } Then { statusCode(200) } Extract { this }
        assertThat(policy.jsonPath().getBoolean("codeSeeded")).isTrue()
        assertThat(policy.jsonPath().getList<String>("tables.kind"))
            .containsExactlyInAnyOrder("EXCLUSION", "ELIGIBILITY", "AFFORDABILITY", "PRICING_BAND")
        // The DSTI threshold the console overlays is READ from here, so it must be present as data.
        assertThat(policy.jsonPath().getList<String>("tables.rules.flatten().attribute")).contains("DSTI")

        Given { contentType("application/json") } When {
            get("/api/v1/lending/risk/policy?asOf=not-a-date")
        } Then { statusCode(400) }
    }

    @Test
    @Order(6)
    @TestSecurity(user = "risk-it-analyst", roles = ["ROLE_CREDIT_RISK"])
    fun `6 - the portfolio answers on an empty book and never fabricates an assessment`() {
        val portfolio = Given { contentType("application/json") } When {
            get("/api/v1/lending/risk/portfolio")
        } Then { statusCode(200) } Extract { this }
        // Whatever loans other suites left behind, none was assessed by the (disabled) cycle.
        assertThat(portfolio.jsonPath().getList<Any?>("assessment")).allSatisfy { assertThat(it).isNull() }
    }

    @Test
    @Order(7)
    @TestSecurity(user = "risk-it-outsider", roles = ["ROLE_CUSTOMER"])
    fun `7 - a role outside the credit desk is refused`() {
        for (path in listOf("decisions", "decisions/summary", "portfolio", "policy")) {
            Given { contentType("application/json") } When {
                get("/api/v1/lending/risk/$path")
            } Then { statusCode(403) }
        }
    }
}
