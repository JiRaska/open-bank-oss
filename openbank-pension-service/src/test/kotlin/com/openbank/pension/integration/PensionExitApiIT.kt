// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.integration

import com.openbank.pension.application.exit.DeathClaimService
import com.openbank.pension.domain.exit.IncentiveBalance
import com.openbank.pension.infrastructure.exit.WorkflowClientTestProducer
import com.openbank.pension.infrastructure.exit.stub.StubFundAdministrationAdapter
import com.openbank.pension.infrastructure.exit.stub.StubIncentiveClawbackAdapter
import com.openbank.pension.infrastructure.exit.stub.StubPayoutPaymentAdapter
import com.openbank.pension.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.restassured.response.ValidatableResponse
import io.smallrye.mutiny.coroutines.asUni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID

/**
 * The exit slice over real HTTP, a real Postgres and the real workflows (in-process Temporal, time
 * skipped). The central assertion is the preview == executed invariant: the amount the payment
 * instruction carries, read back with plain JDBC, equals the net the participant was shown and
 * signed — not a recomputation, even though the fund value moves after the quote.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PensionExitApiIT {

    @Inject
    lateinit var temporal: WorkflowClientTestProducer

    @Inject
    lateinit var fund: StubFundAdministrationAdapter

    @Inject
    lateinit var incentives: StubIncentiveClawbackAdapter

    @Inject
    lateinit var payments: StubPayoutPaymentAdapter

    @Inject
    lateinit var deathClaims: DeathClaimService

    private val contracts = "/api/v1/pension/contracts"
    private val iban = "CZ6508000000192000145399"
    private val party: UUID = UUID.randomUUID()

    private fun req(asParty: UUID? = party, key: String? = UUID.randomUUID().toString()) = given()
        .contentType("application/json")
        .apply { if (asParty != null) header("X-Customer-Party-Id", asParty.toString()) }
        .apply { if (key != null) header("Idempotency-Key", key) }

    private fun activeContract(): UUID {
        val id: String = req().body(
            """
            {"productLine":"DPS","jurisdiction":"CZ","providerEntityId":"${UUID.randomUUID()}",
             "providerType":"PENSION_COMPANY","birthDate":"1985-05-05","residencyCountry":"CZ",
             "schedule":{"amount":1700,"currency":"CZK","frequency":"MONTHLY"},"strategyCode":"BALANCED",
             "beneficiaries":[{"name":"Jane Doe","sharePercent":60},{"name":"John Doe","sharePercent":40}]}
            """.trimIndent(),
        ).post(contracts).then().statusCode(201).extract().path("contractId")
        req().post("$contracts/$id/submit").then().statusCode(200)
        req().post("$contracts/$id/activate").then().statusCode(200)
        return UUID.fromString(id)
    }

    private fun contractStatus(id: UUID): String = req().get("$contracts/$id").then().statusCode(200).extract().path("status")

    private fun awaitStatus(path: String, expected: String, asParty: UUID? = party): ValidatableResponse {
        val deadline = System.nanoTime() + Duration.ofSeconds(AWAIT_SECONDS).toNanos()
        while (true) {
            val r = req(asParty, null).get(path).then().statusCode(200)
            if (r.extract().path<String>("status") == expected || System.nanoTime() > deadline) {
                return r.body("status", equalTo(expected))
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `early termination pays exactly the signed quote after the notice period, once`() {
        val id = activeContract()
        fund.setValue(id, BigDecimal("150000.00"))
        incentives.setBalance(
            id,
            IncentiveBalance(
                BigDecimal("8160.00"), BigDecimal("8160.00"), mapOf(2026 to BigDecimal("20000")), emptyMap(), BigDecimal.ZERO,
            ),
        )
        val quote = req().post("$contracts/$id/exit/termination/quote").then().statusCode(201)
            .body("status", equalTo("QUOTED"))
            .body("quote.incentiveReturn", equalTo(8160.00f))
            .body("quote.deductionRecapture", equalTo(3000.00f))
            .extract()
        val noticeId: String = quote.path("noticeId")
        val quotedNet = BigDecimal(quote.path<Any>("quote.netPayout").toString())
        assertThat(quotedNet).isEqualByComparingTo("138840.00")
        val sign = """{"scaChallengeId":"${UUID.randomUUID()}","payoutIban":"$iban"}"""
        val path = "$contracts/$id/exit/termination/$noticeId"

        // The fund value moves after the quote: the binding quote must still be what is paid.
        fund.setValue(id, BigDecimal("140000.00"))

        req(key = null).body(sign).post("$path/sign").then().statusCode(400)
        req(key = "sign-1").body(sign).post("$path/sign").then().statusCode(200).body("status", equalTo("SIGNED"))
        req(key = "sign-1").body(sign).post("$path/sign").then().statusCode(200).body("status", equalTo("SIGNED"))
        req(key = "sign-2").body(sign).post("$path/sign").then().statusCode(409)
        assertThat(contractStatus(id)).isEqualTo("TERMINATING")

        temporal.env.sleep(Duration.ofDays(29))
        assertThat(instructions(id)).isEmpty()
        temporal.env.sleep(Duration.ofDays(2))
        awaitStatus(path, "COMPLETED").body("paymentRef", org.hamcrest.Matchers.notNullValue())

        val rows = instructions(id)
        assertThat(rows).hasSize(1)
        assertThat(rows.single().first).isEqualTo("EARLY_TERMINATION")
        assertThat(rows.single().second).isEqualByComparingTo(quotedNet)
        assertThat(payments.orders.values.filter { it.contractId == id }.single().amount).isEqualByComparingTo(quotedNet)
        assertThat(contractStatus(id)).isEqualTo("CLOSED")
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `another participant's contract is 404 on every exit route, and a staff-less header-less call is 400`() {
        val id = activeContract()
        fund.setValue(id, BigDecimal("1000"))
        val stranger = UUID.randomUUID()
        req(stranger).post("$contracts/$id/exit/termination/quote").then().statusCode(404)
        req(stranger).get("$contracts/$id/exit/payout-eligibility").then().statusCode(404)
        req(stranger).body("""{"form":"LUMP_SUM"}""").post("$contracts/$id/exit/payouts/quote").then().statusCode(404)
        val noticeId: String = req().post("$contracts/$id/exit/termination/quote").then().statusCode(201).extract().path("noticeId")
        req(stranger).get("$contracts/$id/exit/termination/$noticeId").then().statusCode(404)
        req(stranger, "k").body("""{"scaChallengeId":"x","payoutIban":"$iban"}""")
            .post("$contracts/$id/exit/termination/$noticeId/sign").then().statusCode(404)
        req(null).post("$contracts/$id/exit/termination/quote").then().statusCode(400)
        // a notice id of another contract is not found under this one
        req().get("$contracts/${activeContract()}/exit/termination/$noticeId").then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `a regular payout before the pack's minimum age and duration is refused`() {
        val id = activeContract()
        fund.setValue(id, BigDecimal("1000"))
        req().get("$contracts/$id/exit/payout-eligibility").then().statusCode(200).body("conditionsMet", equalTo(false))
        req().body("""{"form":"LUMP_SUM"}""").post("$contracts/$id/exit/payouts/quote").then().statusCode(409)
        req().body("""{"form":"EARLY_WITHDRAWAL","amount":500}""").post("$contracts/$id/exit/payouts/quote").then().statusCode(409)
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `the customer edge cannot reach the operator death-claim routes`() {
        req().body("""{"contractId":"${UUID.randomUUID()}","dateOfDeath":"2026-01-01","evidenceRef":"x"}""")
            .post("/api/v1/pension/death-claims").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "op-1", roles = ["ROLE_OPERATOR"])
    fun `a death claim freezes the contract, refuses self-approval and pays each verified claimant their share`() {
        val id = activeContract().also { fund.setValue(it, BigDecimal("100000.01")) }
        val notify = """{"contractId":"$id","dateOfDeath":"2026-09-01","evidenceRef":"death-cert-1"}"""
        val claimId: String = req(null, "d-1").body(notify).post("/api/v1/pension/death-claims").then().statusCode(201)
            .body("status", equalTo("NOTIFIED")).extract().path("claimId")
        req(null, "d-1").body(notify).post("/api/v1/pension/death-claims").then().statusCode(201)
            .body("claimId", equalTo(claimId))
        assertThat(contractStatus(id)).isEqualTo("TERMINATING")

        val claimants: List<Map<String, Any>> = req(null).get("/api/v1/pension/death-claims/$claimId").then()
            .statusCode(200).extract().path("claimants")
        val claimPath = "/api/v1/pension/death-claims/$claimId"
        req(null).body("""{"claimants":[{"name":"Jane Doe","sharePercent":70}]}""").put("$claimPath/claimants").then().statusCode(400)
        req(null, "a-0").post("$claimPath/approve").then().statusCode(409) // claimants not verified
        claimants.forEach {
            req(null).body("""{"name":"${it["name"]}","birthDate":"1990-01-01","identityDocumentRef":"ID-1","iban":"$iban"}""")
                .post("$claimPath/claimants/${it["claimantId"]}/verification").then().statusCode(200)
        }
        req(null, "a-1").post("$claimPath/approve").then().statusCode(409) // four-eyes: op-1 registered it

        val approved = VertxContextSupport.subscribeAndAwait {
            CoroutineScope(Dispatchers.Unconfined).async { deathClaims.approve("op-2", UUID.fromString(claimId)) }.asUni()
        }
        assertThat(approved.claimants.map { it.gross }).containsExactlyInAnyOrder(BigDecimal("60000.01"), BigDecimal("40000.00"))
        awaitStatus(claimPath, "SETTLED", null)

        val rows = instructions(id)
        assertThat(rows.map { it.first }).containsOnly("DEATH_BENEFIT")
        assertThat(rows.fold(BigDecimal.ZERO) { a, r -> a + r.second }).isEqualByComparingTo("100000.01")
        assertThat(contractStatus(id)).isEqualTo("CLOSED")
    }

    private fun instructions(contractId: UUID): List<Pair<String, BigDecimal>> {
        val url = ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java)
        val sql = "select purpose, amount from pension_payment_instructions where contract_id = ? order by id"
        return DriverManager.getConnection(url, "openbank", "openbank_secret").use { conn ->
            conn.prepareStatement(sql).use { st ->
                st.setObject(1, contractId)
                st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) to rs.getBigDecimal(2) else null }.toList() }
            }
        }
    }

    private companion object {
        const val AWAIT_SECONDS = 30L
        const val POLL_MILLIS = 200L
    }
}
