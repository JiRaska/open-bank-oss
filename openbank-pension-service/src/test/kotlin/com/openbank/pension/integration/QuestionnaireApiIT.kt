// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.integration

import com.openbank.pension.it.PostgresTestResource
import com.openbank.pension.testsupport.ProviderFixtures
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.response.ValidatableResponse
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * The data-driven questionnaire (issue #12384) over real HTTP and Postgres: question set with
 * prefill, save-and-resume, consistency review, the profile with its "why", warnings with an
 * acknowledgement that records the wording, and supersession kept for audit.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class QuestionnaireApiIT {

    private val apps = "/api/v1/pension/onboarding/applications"
    private val party: UUID = UUID.randomUUID()

    private fun call(method: String, path: String, body: String? = null, asParty: UUID? = party): ValidatableResponse =
        given().contentType("application/json")
            .apply { if (asParty != null) header("X-Customer-Party-Id", asParty.toString()) }
            .apply { if (body != null) body(body) }
            .apply { if (method == "POST" || method == "PUT") header("Idempotency-Key", UUID.randomUUID().toString()) }
            .`when`().request(method, path).then()

    private fun start(
        productLine: String = "DPS",
        providerType: String = "PENSION_COMPANY",
        birth: String = "1990-05-05",
    ) = call(
        "POST",
        apps,
        """{"kind":"NEW_CONTRACT","productLine":"$productLine","jurisdiction":"CZ",
               "providerEntityId":"${ProviderFixtures.ID}","providerType":"$providerType","birthDate":"$birth",
               "residencyCountry":"CZ","schedule":{"amount":1000,"currency":"CZK","frequency":"MONTHLY"}}""",
    ).statusCode(201).extract().path<String>("applicationId")

    private fun dps(objective: String, reaction: String, capacity: String) = """
        {"dps.objective":"$objective","dps.risk_reaction":"$reaction","dps.knowledge":"CORRECT",
         "dps.experience":"OCCASIONALLY","dps.savings":"50K_250K","dps.loss_capacity":"$capacity"}
    """.trimIndent()

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    @Suppress("LongMethod") // one journey, read top to bottom
    fun `a DPS participant resumes a draft, reviews a contradiction, and picks a riskier strategy with a warning`() {
        val id = start()
        call("GET", "$apps/$id/questionnaire?lang=en").statusCode(200)
            .body("questionSetId", equalTo("cz-dps-questionnaire"))
            .body("legalReviewStatus", equalTo("REQUIRES_LEGAL_REVIEW"))
            .body("steps.code", equalTo(listOf("GOAL", "RISK", "CAPACITY", "SUSTAINABILITY")))
            .body("prefill.yearsToRetirement", greaterThan(20))
            .body("progress.complete", equalTo(false))

        // Save-and-resume: a partial draft is kept, an unknown option or a missing key is refused.
        call(
            "PUT",
            "$apps/$id/questionnaire/draft",
            """{"answers":{"dps.objective":"GROWTH","dps.savings":"250K_1M"}}""",
        )
            .statusCode(200).body("progress.nextStep", equalTo("RISK"))
            .body(
                "steps[2].questions.find { it.id == 'dps.loss_capacity' }.options.find { it.code == 'UP_TO_10' }.illustrationCzk",
                equalTo(60000),
            )
        call("PUT", "$apps/$id/questionnaire/draft", """{"answers":{"dps.objective":"YOLO"}}""").statusCode(400)
        given().contentType("application/json").header("X-Customer-Party-Id", party.toString())
            .body("""{"answers":{}}""").`when`().put("$apps/$id/questionnaire/draft").then().statusCode(400)
        call("GET", "$apps/$id/questionnaire").statusCode(200).body("answers.'dps.objective'", equalTo("GROWTH"))
        // Another party's draft reads as absent.
        call("GET", "$apps/$id/questionnaire", asParty = UUID.randomUUID()).statusCode(404)

        // Contradictory final answers come back for review (422) until confirmed.
        val contradictory = dps("PRESERVE", "BUY_MORE", "UP_TO_25")
        call("POST", "$apps/$id/questionnaire", """{"answers":$contradictory}""").statusCode(422)
            .body("inconsistencies.code", hasItem("OBJECTIVE_VS_REACTION"))
        call(
            "POST",
            "$apps/$id/questionnaire",
            """{"answers":$contradictory,"confirmInconsistencies":["OBJECTIVE_VS_REACTION"]}""",
        ).statusCode(200).body("profile.riskClass", equalTo(2))
            .body("profile.confirmedInconsistencies", hasItem("OBJECTIVE_VS_REACTION"))

        // Answering again supersedes; both assessments stay on record.
        call(
            "POST",
            "$apps/$id/questionnaire",
            """{"answers":${dps("GROWTH", "SWITCH_SAFER", "UP_TO_25")},"language":"en"}""",
        )
            .statusCode(200).body("profile.riskClass", equalTo(3))
            .body("profile.why.questionId", hasItem("dps.risk_reaction"))
        assertThat(
            count("select count(*) from pension_suitability_assessments where application_id = ?", id),
        ).isEqualTo(2)
        assertThat(
            count(
                "select count(*) from pension_suitability_assessments where application_id = ? and status = 'SUPERSEDED'",
                id,
            ),
        ).isEqualTo(1)
        call("GET", "$apps/$id/profile?lang=cs").statusCode(200)
            .body("riskLabel", equalTo("BALANCED"))
            .body("why", not(empty<Any>()))
            .body("lossCapacityCzk", equalTo(37500))

        // DYNAMIC (class 5) is above the profile: warned, refused without acknowledgement.
        call("GET", "$apps/$id/warnings?strategyCode=DYNAMIC&lang=en").statusCode(200)
            .body("code", equalTo(listOf("STRATEGY_ABOVE_PROFILE")))
        call("GET", "$apps/$id/warnings").statusCode(400)
        call("POST", "$apps/$id/strategy", """{"strategyCode":"DYNAMIC"}""").statusCode(400)
        // An acknowledgement cannot name a warning the choice does not require.
        call(
            "POST",
            "$apps/$id/warnings/acknowledge",
            """{"strategyCode":"DYNAMIC","warnings":["PRODUCT_NOT_APPROPRIATE"]}""",
        ).statusCode(400)
        call(
            "POST",
            "$apps/$id/warnings/acknowledge",
            """{"strategyCode":"DYNAMIC","warnings":["STRATEGY_ABOVE_PROFILE"],"language":"en"}""",
        ).statusCode(200)
        val doc: String = call("POST", "$apps/$id/strategy", """{"strategyCode":"DYNAMIC"}""").statusCode(200)
            .body("unsuitableChoiceAcknowledged", equalTo(true)).extract().path("keyInformationDocumentId")
        call("POST", "$apps/$id/kid/accept", """{"documentId":"$doc"}""").statusCode(200)
        call("POST", "$apps/$id/sign", """{"scaChallengeId":"sca-${UUID.randomUUID()}"}""").statusCode(200)
            .body("status", equalTo("SIGNED"))
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `a DIP novice gets the appropriateness warning, and a riskier DIP strategy cannot be acknowledged away`() {
        val id = start("DIP", "BANK", "1979-03-14")
        val novice = """
            {"dip.objective":"GROWTH","dip.financial_situation":"EASILY","dip.loss_capacity":"UP_TO_25",
             "dip.risk_reaction":"HOLD","dip.knowledge_bonds":"DONT_KNOW","dip.experience_bonds":"NEVER",
             "dip.knowledge_equity":"DONT_KNOW","dip.experience_equity":"NEVER","dip.sustainability":"AVOID_HARM"}
        """.trimIndent()
        call("POST", "$apps/$id/questionnaire", """{"answers":$novice}""").statusCode(200)
            .body("profile.appropriate", equalTo(false))
            .body("profile.sustainability.categories", containsInAnyOrder("PAI_CONSIDERED"))
            .body("profile.recommendedStrategyWarnings.code", hasItem("PRODUCT_NOT_APPROPRIATE"))
        call("POST", "$apps/$id/strategy", "{}").statusCode(400)
        call(
            "POST",
            "$apps/$id/warnings/acknowledge",
            """{"strategyCode":"EQUITY_GLOBAL","warnings":["STRATEGY_ABOVE_PROFILE"]}""",
        ).statusCode(400)
        val recommended: String = call("GET", "$apps/$id/recommendation").statusCode(200)
            .extract().path("recommendedStrategy")
        call(
            "POST",
            "$apps/$id/warnings/acknowledge",
            """{"strategyCode":"$recommended","warnings":["PRODUCT_NOT_APPROPRIATE"]}""",
        ).statusCode(200)
        call("POST", "$apps/$id/strategy", "{}").statusCode(200).body("status", equalTo("KID_ISSUED"))
    }

    private fun count(sql: String, id: String): Int {
        val url = ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java)
        DriverManager.getConnection(url, "openbank", "openbank_secret").use { conn ->
            conn.prepareStatement(sql).use { st ->
                st.setObject(1, UUID.fromString(id))
                st.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }
}
