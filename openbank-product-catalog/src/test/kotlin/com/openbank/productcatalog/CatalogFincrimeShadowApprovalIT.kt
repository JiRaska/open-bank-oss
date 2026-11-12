// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.productcatalog.domain.catalog.catalogPrincipalKey
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.config.HttpClientConfig.httpClientConfig
import io.restassured.config.RestAssuredConfig.config
import io.restassured.response.ValidatableResponse
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_catalog_fincrime_shadow")],
    restrictToAnnotatedClass = true,
)
@QuarkusTestResource(CatalogPensionOidcTestResource::class, restrictToAnnotatedClass = true)
@TestProfile(CatalogFincrimeShadowApprovalIT.FincrimeShadowProfile::class)
class CatalogFincrimeShadowApprovalIT {
    @Inject
    lateinit var dataSource: DataSource

    @ConfigProperty(name = "openbank.test.pension.issuer")
    lateinit var issuer: String

    @ConfigProperty(name = "openbank.test.pension.secret")
    lateinit var secret: String

    class FincrimeShadowProfile : QuarkusTestProfile {
        override fun getConfigOverrides() = CatalogPensionOidcApprovalProfile().getConfigOverrides() + mapOf(
            "openbank.catalog.packs" to "fincrime-shadow",
            "openbank.catalog.reference-offerings-enabled" to "false",
            "openbank.catalog.bank-v1-compatibility-enabled" to "false",
        )
    }

    @Test
    fun `a changed AML replay requires a new independent exact-digest approval`() {
        val maker = token("aml-maker", "catalog:author fincrime:aml-shadow-approve")
        val reviewer = token("aml-reviewer", "fincrime:aml-shadow-approve")
        val checker = token("catalog-checker", "catalog:publish")
        val fraud = token("fraud-reviewer", "fincrime:fraud-shadow-approve")
        val draft = createDraft(maker, "aml")
        assertThat(makerId(draft)).isEqualTo(catalogPrincipalKey(issuer, subjectOf(maker)))
        given().contentType("application/json").body(REVIEW_REASON).header("If-Match", "\"0\"")
            .post("$draft/approvals/AML_REVIEWER").then().statusCode(401)
        approve(draft, "AML_REVIEWER", maker, 0).statusCode(403)
        approve(draft, "AML_REVIEWER", fraud, 0).statusCode(403)

        renameMaker(subjectOf(maker), "aml-maker-renamed")
        val renamed = token("aml-maker-renamed", "catalog:publish fincrime:aml-shadow-approve")
        assertThat(subjectOf(renamed)).isEqualTo(subjectOf(maker))
        approve(draft, "AML_REVIEWER", renamed, 0).statusCode(403)
            .body("message", equalTo("maker cannot approve their own revision"))
        publish(draft, renamed, 0).statusCode(403)
            .body("message", equalTo("maker cannot publish their own revision"))

        // Change the maker by preparing a separate independently reviewable draft.
        val independentDraft = createDraft(checker, "aml")
        val previous = approve(independentDraft, "AML_REVIEWER", reviewer, 0).statusCode(201)
            .extract().path<String>("digest")
        request(checker).header("If-Match", "\"0\"")
            .body(revision("aml", "d".repeat(64)))
            .put(independentDraft).then().statusCode(200)
        val publisher = token("fraud-reviewer", "catalog:publish")
        publish(independentDraft, publisher, 1).statusCode(409)
        val updated = approve(independentDraft, "AML_REVIEWER", reviewer, 1).statusCode(201)
            .extract().path<String>("digest")
        assertThat(updated).isNotEqualTo(previous)
        publish(independentDraft, publisher, 1).statusCode(200).body("approvalDigest", equalTo(updated))
        assertThat(approvalDigests(independentDraft)).containsExactlyInAnyOrder(previous, updated)
    }

    @Test
    fun `fraud shadow approval needs the fraud authority and nonzero replay`() {
        val maker = token("catalog-checker", "catalog:author")
        val draft = createDraft(maker, "fraud")
        val fraud = token("fraud-reviewer", "fincrime:fraud-shadow-approve")
        val wrongScope = token("fraud-reviewer", "fincrime:aml-shadow-approve")
        val aml = token("aml-reviewer", "fincrime:aml-shadow-approve")
        request(maker).header("If-Match", "\"0\"").body(revision("fraud", evaluated = 0))
            .put(draft).then().statusCode(422)
        approve(draft, "FRAUD_REVIEWER", wrongScope, 0).statusCode(403)
        approve(draft, "FRAUD_REVIEWER", aml, 0).statusCode(403)
        val digest = approve(draft, "FRAUD_REVIEWER", fraud, 0).statusCode(201).extract().path<String>("digest")
        assertThat(approvalDigests(draft)).containsExactly(digest)
    }

    private fun createDraft(maker: String, domain: String): String {
        val schema = mapOf("id" to "org.openbank.fincrime.$domain-count-shadow", "version" to 1)
        val code = "FINCRIME_${UUID.randomUUID().toString().replace("-", "").uppercase()}"
        val specification = request(maker).body(mapOf("code" to code, "schemaRef" to schema))
            .post("/api/v2/specifications").then().statusCode(201).extract().path<String>("id")
        val offering = request(maker).body(
            mapOf("specificationId" to specification, "code" to code, "market" to mapOf("countries" to listOf("CZ"))),
        ).post("/api/v2/offerings").then().statusCode(201).extract().path<String>("id")
        val revision = request(maker).body(revision(domain))
            .post("/api/v2/offerings/$offering/revisions").then().statusCode(201).extract().path<String>("id")
        return "/api/v2/offerings/$offering/revisions/$revision"
    }

    private fun revision(domain: String, digest: String = "c".repeat(64), evaluated: Int = 2) = mapOf(
        "schemaRef" to mapOf("id" to "org.openbank.fincrime.$domain-count-shadow", "version" to 1),
        "name" to mapOf("en" to "Synthetic count replay"),
        "effectiveFrom" to "2030-01-01T00:00:00Z",
        "attributes" to mapOf(
            "mode" to "SHADOW",
            "evidenceClass" to "SYNTHETIC",
            "packId" to "synthetic-$domain-count",
            "baseline" to mapOf("revision" to 1, "hash" to "a".repeat(64)),
            "candidate" to mapOf("revision" to 2, "hash" to "b".repeat(64)),
            "replayDigest" to digest,
            "evaluatedCases" to evaluated,
            "changedCases" to 1,
        ),
    )

    private fun approve(draft: String, role: String, token: String, version: Int): ValidatableResponse =
        request(token).header("If-Match", "\"$version\"").body(REVIEW_REASON).post("$draft/approvals/$role").then()

    private fun publish(draft: String, token: String, version: Int): ValidatableResponse =
        request(token).header("If-Match", "\"$version\"").body(REVIEW_REASON).post("$draft/publish").then()

    private fun approvalDigests(draft: String): List<String> = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT digest FROM catalog_revision_role_approvals WHERE revision_id = ?")
            .use { query ->
                query.setObject(1, UUID.fromString(draft.substringAfterLast('/')))
                query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
    }

    private fun makerId(draft: String): String = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT maker_id FROM catalog_revisions WHERE id = ?").use { query ->
            query.setObject(1, UUID.fromString(draft.substringAfterLast('/')))
            query.executeQuery().use { rows ->
                check(rows.next())
                rows.getString(1)
            }
        }
    }

    private fun subjectOf(token: String) = ObjectMapper()
        .readTree(Base64.getUrlDecoder().decode(token.split('.')[1])).path("sub").asText()

    private fun renameMaker(subject: String, name: String) {
        val base = issuer.substringBeforeLast("/realms/")
        val realm = issuer.substringAfterLast('/')
        val admin = keycloakRequest().contentType("application/x-www-form-urlencoded")
            .formParam("grant_type", "password")
            .formParam("client_id", "admin-cli")
            .formParam("username", "synthetic-test-admin")
            .formParam("password", secret)
            .post("$base/realms/master/protocol/openid-connect/token").then().statusCode(200)
            .extract().path<String>("access_token")
        keycloakRequest().auth().oauth2(admin).contentType("application/json")
            .body(mapOf("username" to name)).put("$base/admin/realms/$realm/users/$subject")
            .then().statusCode(204)
    }

    private fun request(token: String) = given().auth().oauth2(token).contentType("application/json")

    private fun keycloakRequest() = given().config(
        config().httpClient(httpClientConfig().setParam("http.socket.timeout", KEYCLOAK_READ_TIMEOUT_MS)),
    )

    private fun token(user: String, scopes: String): String = keycloakRequest()
        .contentType("application/x-www-form-urlencoded")
        .formParam("grant_type", "password")
        .formParam("client_id", "openbank-admin-ui")
        .formParam("client_secret", secret)
        .formParam("username", user)
        .formParam("password", secret)
        .formParam("scope", "openid profile $scopes")
        .post("$issuer/protocol/openid-connect/token").then().statusCode(200).extract().path("access_token")

    private companion object {
        const val REVIEW_REASON = """{"reason":"Independent synthetic replay review"}"""
        const val KEYCLOAK_READ_TIMEOUT_MS = 30000
    }
}
