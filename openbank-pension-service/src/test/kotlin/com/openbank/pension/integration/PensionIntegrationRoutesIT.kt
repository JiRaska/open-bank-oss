// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.integration

import com.openbank.pension.application.port.`in`.PensionContractUseCase
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.infrastructure.fund.InMemoryFundAdministrationAdapter
import com.openbank.pension.it.PostgresTestResource
import com.openbank.pension.testsupport.ContractFixtures
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.restassured.specification.RequestSpecification
import io.smallrye.mutiny.coroutines.asUni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The routes ADR-0334 S8 adds for customer-edge and the admin UI, plus the replay protection every
 * POST now carries — over real HTTP and a real Postgres.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PensionIntegrationRoutesIT {

    @Inject
    lateinit var contractUseCase: PensionContractUseCase

    @Inject
    lateinit var contractRepository: PensionContractRepository

    @Inject
    lateinit var fund: InMemoryFundAdministrationAdapter

    private val party: UUID = UUID.randomUUID()

    private fun <T> onVertx(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni() }

    private fun edge(asParty: UUID? = party, key: String? = UUID.randomUUID().toString()): RequestSpecification =
        given().contentType("application/json")
            .apply { if (asParty != null) header("X-Customer-Party-Id", asParty.toString()) }
            .apply { if (key != null) header("Idempotency-Key", key) }

    private val startBody = """
        {"kind":"NEW_CONTRACT","productLine":"DPS","jurisdiction":"CZ","providerEntityId":"${UUID.randomUUID()}",
         "providerType":"PENSION_COMPANY","birthDate":"1990-05-05","residencyCountry":"CZ",
         "schedule":{"amount":1000,"currency":"CZK","frequency":"MONTHLY"}}
    """.trimIndent()

    // ---- Idempotency-Key replay ----------------------------------------------------------------

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `a retried POST with the same key is answered from the first response and creates nothing new`() {
        val key = UUID.randomUUID().toString()
        val first = edge(key = key).body(startBody).post("/api/v1/pension/onboarding/applications")
            .then().statusCode(201).extract()
        val replay = edge(key = key).body(startBody).post("/api/v1/pension/onboarding/applications")
            .then().statusCode(201).header("Idempotent-Replayed", "true").extract()
        assertThat(replay.path<String>("applicationId")).isEqualTo(first.path<String>("applicationId"))

        val other = edge().body(startBody).post("/api/v1/pension/onboarding/applications")
            .then().statusCode(201).extract()
        assertThat(other.path<String>("applicationId")).isNotEqualTo(first.path<String>("applicationId"))

        // The same key from ANOTHER participant is another scope: it never reads the first response.
        val stranger = edge(asParty = UUID.randomUUID(), key = key).body(startBody)
            .post("/api/v1/pension/onboarding/applications").then().statusCode(201).extract()
        assertThat(stranger.path<String>("applicationId")).isNotEqualTo(first.path<String>("applicationId"))
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `a POST without an Idempotency-Key is refused before anything runs`() {
        edge(key = null).body(startBody).post("/api/v1/pension/onboarding/applications").then().statusCode(400)
        edge(key = null).body("{}").post("/api/v1/pension/simulations").then().statusCode(400)
    }

    // ---- simulation ------------------------------------------------------------------------------

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `a simulation projects every configured strategy and is labelled illustrative`() {
        edge().body(
            """{"jurisdiction":"CZ","productLine":"DPS","strategyCode":"BALANCED","monthlyContribution":1700,
                "employerMonthlyContribution":500,"horizonYears":25}""",
        ).post("/api/v1/pension/simulations").then().statusCode(200)
            .body("illustrative", equalTo(true))
            .body("disclaimer", notNullValue())
            .body("requestedStrategy", equalTo("BALANCED"))
            .body("projections.strategyCode", hasItem("BALANCED"))
            .body("projections.strategyCode", hasItem("DYNAMIC"))
            .body("projections.find { it.strategyCode == 'BALANCED' }.stateIncentives", equalTo(102000.00f))

        edge().body(
            """{"jurisdiction":"CZ","productLine":"DPS","strategyCode":"NOPE","monthlyContribution":1,"horizonYears":5}""",
        )
            .post("/api/v1/pension/simulations").then().statusCode(400)
        edge().body("""{"jurisdiction":"CZ","productLine":"DPS","monthlyContribution":1,"horizonYears":61}""")
            .post("/api/v1/pension/simulations").then().statusCode(400)
    }

    // ---- operator lists --------------------------------------------------------------------------

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `the customer edge cannot read the operator payout or death-claim queues`() {
        edge().get("/api/v1/pension/operator/payouts").then().statusCode(403)
        edge().get("/api/v1/pension/death-claims").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "ops", roles = ["ROLE_OPERATOR"])
    fun `staff list payouts by contract and death claims`() {
        given().get("/api/v1/pension/operator/payouts?limit=5").then().statusCode(200)
        given().get("/api/v1/pension/operator/payouts?contractId=${UUID.randomUUID()}").then().statusCode(200)
            .body("size()", equalTo(0))
        given().get("/api/v1/pension/death-claims?status=NOTIFIED").then().statusCode(200)
        given().get("/api/v1/pension/operator/payouts?status=NOPE").then().statusCode(404)
    }

    // ---- payout-account change -------------------------------------------------------------------

    private fun retiredContract(): UUID = onVertx {
        ContractFixtures.activeContract(
            contractUseCase,
            contractRepository,
            party,
            birthDate = LocalDate.parse("1960-02-02"),
            startDate = LocalDate.parse("2010-01-01"),
        )
    }.also { fund.setValue(it, BigDecimal("120000.00")) }

    private fun confirmedPayout(contract: UUID, form: String): String {
        val months = if (form == "PHASED_WITHDRAWAL") ""","months":12""" else ""
        val payout: String = edge().body("""{"form":"$form"$months}""")
            .post("/api/v1/pension/contracts/$contract/exit/payouts/quote").then().statusCode(201)
            .extract().path("payoutId")
        edge().body("""{"scaChallengeId":"sca-${UUID.randomUUID()}","payoutIban":"$IBAN"}""")
            .post("/api/v1/pension/contracts/$contract/exit/payouts/$payout/confirm").then().statusCode(200)
        return payout
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `the remaining payments of a phased payout move to another own account under a fresh SCA`() {
        val contract = retiredContract()
        val payout = confirmedPayout(contract, "PHASED_WITHDRAWAL")
        val path = "/api/v1/pension/contracts/$contract/exit/payouts/$payout/account"
        val challenge = "sca-${UUID.randomUUID()}"

        edge(key = null).body("""{"scaChallengeId":"$challenge","payoutIban":"$OTHER_IBAN"}""").put(path)
            .then().statusCode(200).body("payoutAccountLast4", equalTo(OTHER_IBAN.takeLast(4)))
        // The challenge is single-use: spending it again on a different account is refused.
        edge(key = null).body("""{"scaChallengeId":"$challenge","payoutIban":"$IBAN"}""").put(path)
            .then().statusCode(403)
        // Another participant cannot even see the payout.
        edge(asParty = UUID.randomUUID(), key = null)
            .body("""{"scaChallengeId":"sca-${UUID.randomUUID()}","payoutIban":"$IBAN"}""").put(path)
            .then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `a single-payment form has no later payments to redirect`() {
        val contract = retiredContract()
        val payout: String = edge().body("""{"form":"LUMP_SUM"}""")
            .post("/api/v1/pension/contracts/$contract/exit/payouts/quote").then().statusCode(201)
            .extract().path("payoutId")
        edge(key = null).body("""{"scaChallengeId":"sca-${UUID.randomUUID()}","payoutIban":"$OTHER_IBAN"}""")
            .put("/api/v1/pension/contracts/$contract/exit/payouts/$payout/account").then().statusCode(409)
    }

    private companion object {
        const val IBAN = "CZ6508000000192000145399"
        const val OTHER_IBAN = "CZ5508000000001234567899"
    }
}
