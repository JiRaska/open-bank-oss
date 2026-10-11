// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.pension.it.PostgresTestResource
import com.openbank.pension.testsupport.ProviderFixtures
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.Base64
import java.util.UUID

/** Real catalog fast-jar, two databases, a synthetic OIDC issuer and pension HTTP boundary. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(DipCatalogKeycloakResource::class, restrictToAnnotatedClass = true)
@TestProfile(DipPublishedCatalogJourneyIT.RealCatalogClient::class)
class DipPublishedCatalogJourneyIT {
    class RealCatalogClient : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("quarkus.oidc-client.enabled" to "true")
    }

    @Inject lateinit var mapper: ObjectMapper

    @ConfigProperty(name = "openbank.test.pension.catalog-url")
    lateinit var catalogUrl: String

    @ConfigProperty(name = "openbank.test.pension.issuer")
    lateinit var issuer: String

    @ConfigProperty(name = "openbank.test.pension.secret")
    lateinit var secret: String

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    @Suppress("LongMethod") // One ordered two-service publication and suitability proof.
    fun `two human approvals publish DIP strategies consumed by pension suitability`() {
        val maker = humanToken("catalog-maker", "openid profile catalog:read catalog:author")
        val checker = humanToken("catalog-checker", "openid profile catalog:read catalog:publish")
        val lawyer = humanToken("legal-reviewer", "openid profile pension:legal-approve")
        val product = humanToken("product-reviewer", "openid profile pension:product-approve")
        assertThat(jwtSubject(lawyer)).isNotEqualTo(jwtSubject(product))
        val fixturePath = Path.of(System.getProperty("openbank.test.dip.fixture"))
        val dip = mapper.readTree(fixturePath.toFile())["offerings"].first {
            it["attributes"]["productLine"].asText() == "DIP"
        }
        val code = "DIP_E2E_${UUID.randomUUID().toString().replace("-", "").uppercase()}"
        val spec = catalogPost(
            "/api/v2/specifications",
            maker,
            """{"code":"$code","schemaRef":{"id":"org.openbank.retirement.pension-savings","version":2}}""",
            201,
        )
            .path<String>("id")
        val strategies =
            listOf("CONSERVATIVE", "BALANCED", "SUSTAINABLE_BALANCED", "DYNAMIC", "EQUITY_GLOBAL", "LIFECYCLE")
        val revisions = strategies.map { strategy ->
            val offering = catalogPost(
                "/api/v2/offerings",
                maker,
                """{"specificationId":"$spec","code":"${code}_$strategy","market":{"countries":["CZ"]}}""",
                201,
            )
                .path<String>("id")
            val attributes = dip["attributes"].deepCopy<ObjectNode>().apply {
                put("fundStrategy", strategy)
                put("reviewStatus", "LEGAL_AND_COMMERCIAL_REVIEWED")
                putArray("instrumentClasses").add("BOND_FUNDS").add("EQUITY_FUNDS")
            }
            val revision = catalogPost(
                "/api/v2/offerings/$offering/revisions",
                maker,
                mapper.writeValueAsString(
                    mapOf(
                        "schemaRef" to mapOf("id" to "org.openbank.retirement.pension-savings", "version" to 2),
                        "name" to mapOf("en" to dip["name"].asText()),
                        "attributes" to attributes,
                        "effectiveFrom" to "2020-01-01T00:00:00Z",
                    ),
                ),
                201,
            ).path<String>("id")
            offering to revision
        }
        val first = revisions.first()
        val publish = "/api/v2/offerings/${first.first}/revisions/${first.second}/publish"
        catalogPost(publish, checker, """{"reason":"synthetic publication proof"}""", 409, true)
        revisions.forEach { (offering, revision) ->
            val base = "/api/v2/offerings/$offering/revisions/$revision"
            catalogPost(
                "$base/pension-approvals/LEGAL_COUNSEL",
                lawyer,
                """{"reason":"synthetic legal review"}""",
                201,
                true,
            )
            if (offering == first.first) {
                catalogPost(publish, checker, """{"reason":"synthetic publication proof"}""", 409, true)
            }
            catalogPost(
                "$base/pension-approvals/PRODUCT_OWNER",
                product,
                """{"reason":"synthetic product review"}""",
                201,
                true,
            )
            val published = catalogPost(
                "$base/publish",
                checker,
                """{"reason":"synthetic publication proof"}""",
                200,
                true,
            )
            assertThat(published.path<String>("state")).isEqualTo("PUBLISHED")
            val approvals = given().baseUri(catalogUrl).auth().oauth2(lawyer)
                .get("$base/pension-approvals").then().statusCode(200).extract()
                .path<List<Map<String, String>>>("")
            assertThat(approvals.map { it["role"] }).containsExactlyInAnyOrder("LEGAL_COUNSEL", "PRODUCT_OWNER")
            assertThat(approvals.map { it["digest"] }.toSet()).hasSize(1)
            given().baseUri(catalogUrl).auth().oauth2(checker)
                .get("/api/v2/products/$offering?effectiveAt=2027-01-01T00:00:00Z")
                .then().statusCode(200)
        }

        val before = assessDipNovice()
        assertThat(before.warnings).contains("PRODUCT_NOT_APPROPRIATE")
        val changedOffering = revisions[strategies.indexOf(before.recommended)].first
        val bondOnly = dip["attributes"].deepCopy<ObjectNode>().apply {
            put("fundStrategy", before.recommended)
            put("reviewStatus", "LEGAL_AND_COMMERCIAL_REVIEWED")
            putArray("instrumentClasses").add("BOND_FUNDS")
        }
        val newRevision = catalogPost(
            "/api/v2/offerings/$changedOffering/revisions",
            maker,
            mapper.writeValueAsString(
                mapOf(
                    "schemaRef" to mapOf("id" to "org.openbank.retirement.pension-savings", "version" to 2),
                    "name" to mapOf("en" to dip["name"].asText()),
                    "attributes" to bondOnly,
                    "effectiveFrom" to "2026-01-01T00:00:00Z",
                ),
            ),
            201,
        ).path<String>("id")
        val updatedBase = "/api/v2/offerings/$changedOffering/revisions/$newRevision"
        catalogPost(
            "$updatedBase/pension-approvals/LEGAL_COUNSEL",
            lawyer,
            """{"reason":"synthetic revised composition review"}""",
            201,
            true,
        )
        catalogPost(
            "$updatedBase/pension-approvals/PRODUCT_OWNER",
            product,
            """{"reason":"synthetic revised composition review"}""",
            201,
            true,
        )
        catalogPost(
            "$updatedBase/publish",
            checker,
            """{"reason":"synthetic revised composition publication"}""",
            200,
            true,
        )
        val after = assessDipNovice()
        assertThat(after.recommended).isEqualTo(before.recommended)
        assertThat(after.warnings).doesNotContain("PRODUCT_NOT_APPROPRIATE")
    }

    private data class DipAssessment(val recommended: String, val warnings: List<String>)

    private fun assessDipNovice(): DipAssessment {
        val party = UUID.randomUUID()
        val app = given().contentType("application/json")
            .header("X-Customer-Party-Id", party.toString())
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(
                """{"kind":"NEW_CONTRACT","productLine":"DIP","jurisdiction":"CZ",
                "providerEntityId":"${ProviderFixtures.ID}","providerType":"BANK","birthDate":"1979-03-14",
                "residencyCountry":"CZ","schedule":{"amount":1000,"currency":"CZK","frequency":"MONTHLY"}}""",
            )
            .post("/api/v2/pension/onboarding/applications").then().statusCode(201)
            .extract().path<String>("applicationId")
        val questionnaire = """{"answers":{"dip.objective":"GROWTH","dip.financial_situation":"EASILY",
            "dip.loss_capacity":"UP_TO_25","dip.risk_reaction":"HOLD","dip.knowledge_bonds":"CORRECT",
            "dip.experience_bonds":"REGULARLY","dip.knowledge_equity":"DONT_KNOW",
            "dip.experience_equity":"NEVER","dip.sustainability":"AVOID_HARM"}}"""
        val response = given().contentType("application/json")
            .header("X-Customer-Party-Id", party.toString())
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(questionnaire).post("/api/v2/pension/onboarding/applications/$app/questionnaire")
            .then().statusCode(200).extract()
        val recommended = given().header("X-Customer-Party-Id", party.toString())
            .get("/api/v2/pension/onboarding/applications/$app/recommendation")
            .then().statusCode(200).extract().path<String>("recommendedStrategy")
        return DipAssessment(
            recommended,
            response.path<List<Map<String, Any>>>("profile.recommendedStrategyWarnings")
                .map { it["code"].toString() },
        )
    }

    private fun catalogPost(path: String, token: String, body: String, status: Int, ifMatch: Boolean = false) =
        given().baseUri(catalogUrl).auth().oauth2(token).contentType("application/json")
            .apply { if (ifMatch) header("If-Match", "\"0\"") }
            .body(body).post(path).then().statusCode(status).extract()

    private fun humanToken(user: String, scopes: String): String = given()
        .contentType("application/x-www-form-urlencoded")
        .formParam("grant_type", "password")
        .formParam("client_id", "openbank-admin-ui")
        .formParam("client_secret", secret)
        .formParam("username", user)
        .formParam("password", secret)
        .formParam("scope", scopes)
        .post("$issuer/protocol/openid-connect/token").then().statusCode(200)
        .extract().path("access_token")

    private fun jwtSubject(token: String): String = mapper.readTree(
        Base64.getUrlDecoder().decode(token.split('.')[1]),
    ).path("sub").asText()
}
