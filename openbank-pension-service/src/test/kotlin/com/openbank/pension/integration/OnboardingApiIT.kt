// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.integration

import com.openbank.pension.application.port.out.ActivationOutcome
import com.openbank.pension.application.port.out.OnboardingActivationPort
import com.openbank.pension.infrastructure.onboarding.temporal.onWorker
import com.openbank.pension.it.PostgresTestResource
import com.openbank.pension.testsupport.ProviderFixtures
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.response.ValidatableResponse
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * Onboarding and transfers over real HTTP against real Postgres, with the real workflows running
 * in an in-process Temporal (PensionTemporalTestEnvironment). Ownership is tested as the absence of
 * data: another party's application or transfer answers 404 exactly like an unknown id.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class OnboardingApiIT {

    private val apps = "/api/v2/pension/onboarding/applications"
    private val transfers = "/api/v2/pension/transfers"
    private val party: UUID = UUID.randomUUID()

    private fun startBody(kind: String = "NEW_CONTRACT", extra: String = "") = """
        {
          "kind": "$kind",
          "productLine": "DPS",
          "jurisdiction": "CZ",
          "providerEntityId": "${ProviderFixtures.ID}",
          "providerType": "PENSION_COMPANY",
          "birthDate": "1990-05-05",
          "residencyCountry": "CZ",
          "schedule": { "amount": 1000, "currency": "CZK", "frequency": "MONTHLY" }
          $extra
        }
    """.trimIndent()

    private fun call(method: String, path: String, body: String? = null, asParty: UUID? = party): ValidatableResponse =
        given().contentType("application/json")
            .apply { if (asParty != null) header("X-Customer-Party-Id", asParty.toString()) }
            .apply { if (body != null) body(body) }
            // Every POST requires an Idempotency-Key since S8; a fresh one per call, so each call runs.
            .apply { if (method == "POST") header("Idempotency-Key", UUID.randomUUID().toString()) }
            .`when`().request(method, path).then()

    private fun start(kind: String = "NEW_CONTRACT", extra: String = ""): String =
        call("POST", apps, startBody(kind, extra)).statusCode(201).body("status", equalTo("STARTED"))
            .extract().path("applicationId")

    /** Questionnaire → recommendation → strategy + KID → acceptance; returns the application id. */
    private fun readyToSign(kind: String = "NEW_CONTRACT", extra: String = ""): String {
        val id = start(kind, extra)
        call(
            "POST",
            "$apps/$id/questionnaire",
            """{"riskAppetite":2,"lossTolerance":2,"financialSituationStable":true}""",
        )
            .statusCode(200).body("recommendation.recommendedStrategy", equalTo("LIFECYCLE"))
        call("GET", "$apps/$id/recommendation").statusCode(200).body("maxRiskClass", equalTo(5))
        val doc: String = call("POST", "$apps/$id/strategy", "{}").statusCode(200)
            .body("status", equalTo("KID_ISSUED")).body("chosenStrategy", equalTo("LIFECYCLE"))
            .extract().path("keyInformationDocumentId")
        call(
            "POST",
            "$apps/$id/kid/accept",
            """{"documentId":"$doc"}""",
        ).statusCode(200).body("status", equalTo("KID_ACCEPTED"))
        return id
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `creation for another provider is a bad request`() {
        val body = startBody().replace(
            ProviderFixtures.ID.toString(),
            UUID.randomUUID().toString(),
        )
        call("POST", apps, body).statusCode(400)
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `a new contract is signed, held in cooling-off, and withdrawn with its contract closed`() {
        val id = readyToSign()
        val contractId: String = call("POST", "$apps/$id/sign", """{"scaChallengeId":"sca-${UUID.randomUUID()}"}""")
            .statusCode(200).body("status", equalTo("SIGNED")).body("coolingOffEndsOn", notNullValue())
            .extract().path("contractId")
        assertThat(contractStatus(contractId)).isEqualTo("PENDING_ACTIVATION")

        call("POST", "$apps/$id/withdraw").statusCode(200).body("status", equalTo("WITHDRAWN"))
        assertThat(contractStatus(contractId)).isEqualTo("CLOSED")
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `steps cannot be skipped and an SCA challenge cannot be spent twice`() {
        val early = start()
        call("POST", "$apps/$early/sign", """{"scaChallengeId":"sca-x"}""").statusCode(409)
        call("POST", "$apps/$early/kid/accept", """{"documentId":"anything"}""").statusCode(409)

        val challenge = "sca-${UUID.randomUUID()}"
        call("POST", "$apps/${readyToSign()}/sign", """{"scaChallengeId":"$challenge"}""").statusCode(200)
        call("POST", "$apps/${readyToSign()}/sign", """{"scaChallengeId":"$challenge"}""").statusCode(422)
        call("POST", "$apps/${readyToSign()}/sign", """{"scaChallengeId":"rejected-1"}""").statusCode(422)
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `another party's application reads as absent, and the party comes only from the header`() {
        val id = start()
        val stranger = UUID.randomUUID()
        call("GET", "$apps/$id", asParty = stranger).statusCode(404)
        call(
            "POST",
            "$apps/$id/questionnaire",
            """{"riskAppetite":1,"lossTolerance":1,"financialSituationStable":true}""",
            stranger,
        )
            .statusCode(404)
        call("POST", "$apps/$id/abandon", asParty = stranger).statusCode(404)
        call("GET", "$apps/$id", asParty = null).statusCode(400)
        // Claiming to act for someone without a verified guardianship is refused, not honoured.
        call("POST", apps, startBody(extra = """, "onBehalfOfPartyId": "${UUID.randomUUID()}" """)).statusCode(400)
        call("GET", "$apps/$id").statusCode(200).body("status", equalTo("STARTED"))
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `the customer edge cannot reach operator or provider routes`() {
        call("GET", "/api/v2/pension/operator/onboarding/applications").statusCode(403)
        call(
            "POST",
            "/api/v2/pension/provider/transfers/out",
            """{"contractId":"${UUID.randomUUID()}","receivingProviderId":"P2","receivingProviderName":"Other","receivingContractNumber":"C-1"}""",
        ).statusCode(403)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_API"])
    fun `a party header from any principal but the edge relay is refused`() {
        call("POST", apps, startBody()).statusCode(403)
        call("GET", "$apps/${UUID.randomUUID()}").statusCode(403)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `operators list applications and transfers but cannot use the client routes`() {
        given().`when`().get("/api/v2/pension/operator/onboarding/applications?limit=5").then().statusCode(200)
        given().`when`().get("/api/v2/pension/operator/transfers?status=COMPLETED").then().statusCode(200)
        call("POST", apps, startBody()).statusCode(403)
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `a transfer-in is sent, and withdrawing before the funds cancels it at the ceding provider`() {
        val id = readyToSign(
            "TRANSFER_IN",
            """, "transferIn": {"providerId":"P1","providerName":"Ceding","contractNumber":"OLD-1"}""",
        )
        val transferId: String = call("POST", "$apps/$id/sign", """{"scaChallengeId":"sca-${UUID.randomUUID()}"}""")
            .statusCode(200).extract().path("transferRequestId")
        awaitTransfer(transferId, "SENT")
        call("GET", "$transfers/$transferId", asParty = UUID.randomUUID()).statusCode(404)

        call("POST", "$apps/$id/withdraw").statusCode(200).body("status", equalTo("WITHDRAWN"))
        awaitTransfer(transferId, "CANCELLED")
        call("GET", "$transfers/$transferId").statusCode(200)
            .body("compensation", equalTo("CEDING_CANCELLED_AND_CONTRACT_CLOSED"))
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `a transfer-out of an inactive or foreign contract is refused`() {
        val body = """{"contractId":"${UUID.randomUUID()}","receivingProviderId":"P2","receivingProviderName":"Other",
            |"receivingContractNumber":"C-1","scaChallengeId":"sca-${UUID.randomUUID()}"}
        """.trimMargin()
        call("POST", "$transfers/out", body).statusCode(404)
        val id = readyToSign()
        val contractId: String = call("POST", "$apps/$id/sign", """{"scaChallengeId":"sca-${UUID.randomUUID()}"}""")
            .statusCode(200).extract().path("contractId")
        val own = body.replace(Regex("\"contractId\":\"[^\"]+\""), "\"contractId\":\"$contractId\"")
        call("POST", "$transfers/out", own, UUID.randomUUID()).statusCode(404)
        call("POST", "$transfers/out", own).statusCode(409)
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `the edge's transfer-in route starts a TRANSFER_IN application`() {
        val ceding = """, "transferIn": {"providerId":"P1","providerName":"Ceding","contractNumber":"OLD-2"}"""
        call("POST", "/api/v2/pension/contracts/transfers-in", startBody("NEW_CONTRACT", ceding)).statusCode(201)
            .body("kind", equalTo("TRANSFER_IN")).body("status", equalTo("STARTED"))
        call("POST", "/api/v2/pension/contracts/transfers-in", startBody()).statusCode(400)
    }

    @Inject
    lateinit var activation: OnboardingActivationPort

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `a first contribution reaches the workflow of a signed new contract, and nothing else`() {
        val contractId: String = call(
            "POST",
            "$apps/${readyToSign()}/sign",
            """{"scaChallengeId":"sca-${UUID.randomUUID()}"}""",
        )
            .statusCode(200).extract().path("contractId")
        // A bare test thread has no Vert.x context; the same bridge the activities use supplies one.
        val signalled = onWorker { activation.firstContributionReceived(UUID.fromString(contractId)) }
        assertThat(signalled).isEqualTo(ActivationOutcome.SIGNALLED)
        val unknown = onWorker { activation.firstContributionReceived(UUID.randomUUID()) }
        assertThat(unknown).isEqualTo(ActivationOutcome.NOT_AWAITING)
        // Inside the cooling-off period the contract stays PENDING_ACTIVATION.
        assertThat(contractStatus(contractId)).isEqualTo("PENDING_ACTIVATION")
    }

    private fun awaitTransfer(id: String, status: String) {
        val deadline = System.currentTimeMillis() + AWAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (transferStatus(id) == status) return
            Thread.sleep(POLL_MS)
        }
        assertThat(transferStatus(id)).isEqualTo(status)
    }

    private fun contractStatus(id: String) = single("select status from pension_contracts where contract_id = ?", id)

    private fun transferStatus(id: String) =
        single("select status from pension_transfer_requests where transfer_id = ?", id)

    private fun single(sql: String, id: String): String? {
        val url = ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java)
        val conn = DriverManager.getConnection(url, "openbank", "openbank_secret")
        val st = conn.prepareStatement(sql).apply { setObject(1, UUID.fromString(id)) }
        val rs = st.executeQuery()
        return try {
            if (rs.next()) rs.getString(1) else null
        } finally {
            rs.close()
            st.close()
            conn.close()
        }
    }

    private companion object {
        const val AWAIT_MS = 15_000L
        const val POLL_MS = 200L
    }
}
