// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.integration

import com.openbank.pension.application.port.`in`.PensionContractUseCase
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.infrastructure.exit.stub.StubParticipantNotificationAdapter
import com.openbank.pension.infrastructure.fund.InMemoryFundAdministrationAdapter
import com.openbank.pension.it.PostgresTestResource
import com.openbank.pension.testsupport.ContractFixtures
import com.openbank.pension.testsupport.ProviderFixtures
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
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
@TestProfile(PensionIntegrationRoutesIT.OwnTemporal::class)
class PensionIntegrationRoutesIT {

    /**
     * Its OWN Quarkus app, so its OWN time-skipping Temporal environment (#12383). Every
     * default-profile class shares one environment, and `PensionExitApiIT` advances its workflow
     * time by 31 days; a payout confirmed here afterwards had its first instalment already "due",
     * so the instalment activity raced this class's account-change assertions and the result
     * depended on test ORDER (409 in some full-module runs, green alone). The production race
     * itself is covered deterministically by `PayoutAccountChangeRaceTest`.
     */
    class OwnTemporal : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.pension.onboarding.task-queue" to "it-routes-pension-onboarding",
            "openbank.pension.exit.task-queue" to "it-routes-pension-exit",
        )
    }

    @Inject
    lateinit var contractUseCase: PensionContractUseCase

    @Inject
    lateinit var contractRepository: PensionContractRepository

    @Inject
    lateinit var fund: InMemoryFundAdministrationAdapter

    @Inject
    lateinit var notifications: StubParticipantNotificationAdapter

    private val party: UUID = UUID.randomUUID()

    private fun <T> onVertx(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni() }

    private fun edge(asParty: UUID? = party, key: String? = UUID.randomUUID().toString()): RequestSpecification =
        given().contentType("application/json")
            .apply { if (asParty != null) header("X-Customer-Party-Id", asParty.toString()) }
            .apply { if (key != null) header("Idempotency-Key", key) }

    private val startBody = """
        {"kind":"NEW_CONTRACT","productLine":"DPS","jurisdiction":"CZ","providerEntityId":"${ProviderFixtures.ID}",
         "providerType":"PENSION_COMPANY","birthDate":"1990-05-05","residencyCountry":"CZ",
         "schedule":{"amount":1000,"currency":"CZK","frequency":"MONTHLY"}}
    """.trimIndent()

    // ---- Idempotency-Key replay ----------------------------------------------------------------

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `a retried POST with the same key is answered from the first response and creates nothing new`() {
        val key = UUID.randomUUID().toString()
        val first = edge(key = key).body(startBody).post("/api/v2/pension/onboarding/applications")
            .then().statusCode(201).extract()
        val replay = edge(key = key).body(startBody).post("/api/v2/pension/onboarding/applications")
            .then().statusCode(201).header("Idempotent-Replayed", "true").extract()
        assertThat(replay.path<String>("applicationId")).isEqualTo(first.path<String>("applicationId"))

        val other = edge().body(startBody).post("/api/v2/pension/onboarding/applications")
            .then().statusCode(201).extract()
        assertThat(other.path<String>("applicationId")).isNotEqualTo(first.path<String>("applicationId"))

        // The same key from ANOTHER participant is another scope: it never reads the first response.
        val stranger = edge(asParty = UUID.randomUUID(), key = key).body(startBody)
            .post("/api/v2/pension/onboarding/applications").then().statusCode(201).extract()
        assertThat(stranger.path<String>("applicationId")).isNotEqualTo(first.path<String>("applicationId"))
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `a POST without an Idempotency-Key is refused before anything runs`() {
        edge(key = null).body(startBody).post("/api/v2/pension/onboarding/applications").then().statusCode(400)
        edge(key = null).body("{}").post("/api/v2/pension/simulations").then().statusCode(400)
    }

    // ---- simulation ------------------------------------------------------------------------------

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `a simulation projects every configured strategy and is labelled illustrative`() {
        edge().body(
            """{"jurisdiction":"CZ","productLine":"DPS","strategyCode":"BALANCED","monthlyContribution":1700,
                "employerMonthlyContribution":500,"horizonYears":25}""",
        ).post("/api/v2/pension/simulations").then().statusCode(200)
            .body("illustrative", equalTo(true))
            .body("disclaimer", notNullValue())
            .body("requestedStrategy", equalTo("BALANCED"))
            .body("projections.strategyCode", hasItem("BALANCED"))
            .body("projections.strategyCode", hasItem("DYNAMIC"))
            .body("projections.find { it.strategyCode == 'BALANCED' }.stateIncentives", equalTo(102000.00f))

        edge().body(
            """{"jurisdiction":"CZ","productLine":"DPS","strategyCode":"NOPE","monthlyContribution":1,"horizonYears":5}""",
        )
            .post("/api/v2/pension/simulations").then().statusCode(400)
        edge().body("""{"jurisdiction":"CZ","productLine":"DPS","monthlyContribution":1,"horizonYears":61}""")
            .post("/api/v2/pension/simulations").then().statusCode(400)
    }

    // ---- operator lists --------------------------------------------------------------------------

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `the customer edge cannot read the operator payout or death-claim queues`() {
        edge().get("/api/v2/pension/operator/payouts").then().statusCode(403)
        edge().get("/api/v2/pension/death-claims").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "ops", roles = ["ROLE_OPERATOR"])
    fun `staff list payouts by contract and death claims`() {
        given().get("/api/v2/pension/operator/payouts?limit=5").then().statusCode(200)
        given().get("/api/v2/pension/operator/payouts?contractId=${UUID.randomUUID()}").then().statusCode(200)
            .body("size()", equalTo(0))
        given().get("/api/v2/pension/death-claims?status=NOTIFIED").then().statusCode(200)
        given().get("/api/v2/pension/operator/payouts?status=NOPE").then().statusCode(404)
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
            .post("/api/v2/pension/contracts/$contract/exit/payouts/quote").then().statusCode(201)
            .extract().path("payoutId")
        edge().body("""{"scaChallengeId":"sca-${UUID.randomUUID()}","payoutIban":"$IBAN"}""")
            .post("/api/v2/pension/contracts/$contract/exit/payouts/$payout/confirm").then().statusCode(200)
        return payout
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `an account change is held, notified and never redirects the signed account or a pending change`() {
        val contract = retiredContract()
        val payout = confirmedPayout(contract, "PHASED_WITHDRAWAL")
        val path = "/api/v2/pension/contracts/$contract/exit/payouts/$payout/account"

        edge(key = null).body("""{"scaChallengeId":"sca-${UUID.randomUUID()}","payoutIban":"$OTHER_IBAN"}""").put(path)
            .then().statusCode(200)
            // The signed account is untouched; the new one is pending for later installments only.
            .body("payoutAccountLast4", equalTo(IBAN.takeLast(4)))
            .body("pendingAccountLast4", equalTo(OTHER_IBAN.takeLast(4)))
            .body("pendingAccountFrom", equalTo(LocalDate.now(java.time.ZoneOffset.UTC).plusDays(3).toString()))
        assertThat(notifications.sent).anySatisfy { assertThat(it).startsWith("$payout|${OTHER_IBAN.takeLast(4)}") }

        // A second change while one is pending is refused (no rapid chain of redirects).
        edge(key = null).body("""{"scaChallengeId":"sca-${UUID.randomUUID()}","payoutIban":"$IBAN"}""").put(path)
            .then().statusCode(409)
        // Another participant cannot even see the payout.
        edge(asParty = UUID.randomUUID(), key = null)
            .body("""{"scaChallengeId":"sca-${UUID.randomUUID()}","payoutIban":"$IBAN"}""").put(path)
            .then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `an account change before the payout is confirmed is refused - the account is part of the signature`() {
        val contract = retiredContract()
        val payout: String = edge().body("""{"form":"PHASED_WITHDRAWAL","months":12}""")
            .post("/api/v2/pension/contracts/$contract/exit/payouts/quote").then().statusCode(201)
            .extract().path("payoutId")
        edge(key = null).body("""{"scaChallengeId":"sca-${UUID.randomUUID()}","payoutIban":"$OTHER_IBAN"}""")
            .put("/api/v2/pension/contracts/$contract/exit/payouts/$payout/account").then().statusCode(409)
    }

    /**
     * Two confirmations of one quote race, each with its own valid SCA challenge and its own
     * account: exactly one wins (optimistic lock), and the stored, signed account is the winner's.
     */
    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `concurrent confirmations of one quote - exactly one wins and its signed account is the one stored`() {
        val contract = retiredContract()
        val payout: String = edge().body("""{"form":"PHASED_WITHDRAWAL","months":12}""")
            .post("/api/v2/pension/contracts/$contract/exit/payouts/quote").then().statusCode(201)
            .extract().path("payoutId")
        val confirm = "/api/v2/pension/contracts/$contract/exit/payouts/$payout/confirm"
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val start = java.util.concurrent.CountDownLatch(1)
        val attempts = listOf(IBAN, OTHER_IBAN).map { iban ->
            pool.submit<Pair<String, Int>> {
                start.await()
                iban to edge().body("""{"scaChallengeId":"sca-${UUID.randomUUID()}","payoutIban":"$iban"}""")
                    .post(confirm).then().extract().statusCode()
            }
        }
        start.countDown()
        val results = attempts.map { it.get() }
        pool.shutdown()
        val winners = results.filter { it.second == 200 }
        assertThat(winners).describedAs("results %s", results).hasSize(1)
        assertThat(results.filter { it.second != 200 }.map { it.second }).allMatch { it == 409 }
        edge(key = null).get("/api/v2/pension/contracts/$contract/exit/payouts/$payout").then().statusCode(200)
            .body("payoutAccountLast4", equalTo(winners.single().first.takeLast(4)))
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `a single-payment form has no later payments to redirect`() {
        val contract = retiredContract()
        val payout: String = edge().body("""{"form":"LUMP_SUM"}""")
            .post("/api/v2/pension/contracts/$contract/exit/payouts/quote").then().statusCode(201)
            .extract().path("payoutId")
        edge(key = null).body("""{"scaChallengeId":"sca-${UUID.randomUUID()}","payoutIban":"$OTHER_IBAN"}""")
            .put("/api/v2/pension/contracts/$contract/exit/payouts/$payout/account").then().statusCode(409)
    }

    private companion object {
        const val IBAN = "CZ6508000000192000145399"
        const val OTHER_IBAN = "CZ5508000000001234567899"
    }
}
