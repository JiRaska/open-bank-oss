// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/** Real service and human JWTs through the HTTP resource at catalog's advisory OPA setting. */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_catalog_pension_oidc")],
)
@QuarkusTestResource(CatalogPensionOidcTestResource::class, restrictToAnnotatedClass = true)
@TestProfile(CatalogPensionOidcApprovalProfile::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CatalogPensionOidcApprovalIT {
    @Inject
    lateinit var dataSource: DataSource

    @ConfigProperty(name = "openbank.test.pension.issuer")
    lateinit var issuer: String

    @ConfigProperty(name = "openbank.test.pension.secret")
    lateinit var secret: String

    @Test
    @Order(3)
    fun `pension service JWT gains scoped read and unrelated service JWT is forbidden`() {
        val path = "/api/v2/offerings/${UUID.randomUUID()}/revisions/${UUID.randomUUID()}/pension-approvals"
        given().get(path).then().statusCode(401)
        val unrelated = serviceToken("unrelated-service")
        assertThat(jwtPayload(unrelated).path("scope").asText().split(' ')).contains("catalog:read")
        assertThat(jwtPayload(unrelated).path("sub").asText()).isNotBlank()
        given().auth().oauth2(unrelated).get("/api/v2/offerings").then().statusCode(200)
        given().auth().oauth2(unrelated).get(path).then().statusCode(403)
        val unmarked = serviceToken("unmarked-service")
        assertThat(jwtPayload(unmarked).path("scope").asText().split(' ')).contains("catalog:read")
        assertThat(jwtPayload(unmarked).has("preferred_username")).isFalse()
        assertThat(jwtPayload(unmarked).path("realm_access").path("roles").map { it.asText() })
            .doesNotContain("ROLE_API")
        given().auth().oauth2(unmarked).get("/api/v2/offerings").then().statusCode(200)
        given().auth().oauth2(unmarked).get(path).then().statusCode(403)
        val pension = serviceToken("openbank-pension")
        assertThat(jwtPayload(pension).path("sub").asText()).isNotBlank()
        given().auth().oauth2(pension).get(path).then().statusCode(404)
        val legal = humanToken("legal-reviewer", "openid profile pension:legal-approve")
        given().auth().oauth2(legal).get(path).then().statusCode(404)
    }

    @Test
    @Order(1)
    @TestSecurity(user = "catalog-seed-operator", roles = ["ROLE_OPERATOR"])
    fun `seed a draft with an actor distinct from both human approvers`() {
        val fixture = requireNotNull(javaClass.getResourceAsStream("/retirement-pack/cz-illustrative-offerings.json"))
            .use {
                ObjectMapper().readTree(it)["offerings"].first { offering ->
                    offering["attributes"]["productLine"].asText() == "DIP"
                }
            }
        val code = "CZ_DIP_OIDC_${UUID.randomUUID().toString().replace("-", "").uppercase()}"
        val specificationId = given().contentType("application/json")
            .body("""{"code":"$code","schemaRef":{"id":"org.openbank.retirement.pension-savings","version":2}}""")
            .post("/api/v2/specifications").then().statusCode(201).extract().path<String>("id")
        val offeringId = given().contentType("application/json")
            .body("""{"specificationId":"$specificationId","code":"$code","market":{"countries":["CZ"]}}""")
            .post("/api/v2/offerings").then().statusCode(201).extract().path<String>("id")
        val attributes = fixture["attributes"].deepCopy<ObjectNode>().apply {
            putArray("instrumentClasses").add("BOND_FUNDS").add("EQUITY_FUNDS")
            put("reviewStatus", "LEGAL_AND_COMMERCIAL_REVIEWED")
        }
        val revision = mapOf(
            "schemaRef" to mapOf("id" to "org.openbank.retirement.pension-savings", "version" to 2),
            "name" to mapOf("en" to fixture["name"].asText()),
            "attributes" to attributes,
            "effectiveFrom" to "2027-05-01T00:00:00Z",
        )
        val revisionId = given().contentType("application/json").body(ObjectMapper().writeValueAsString(revision))
            .post("/api/v2/offerings/$offeringId/revisions").then().statusCode(201)
            .extract().path<String>("id")
        seededOfferingId = UUID.fromString(offeringId)
        seededRevisionId = UUID.fromString(revisionId)
    }

    @Test
    @Order(2)
    fun `real legal and product JWTs pass only their own approval role with requested scope`() {
        val revisionId = requireNotNull(seededRevisionId)
        val path = "/api/v2/offerings/${requireNotNull(seededOfferingId)}/revisions/$revisionId/pension-approvals"
        val legal = humanToken("legal-reviewer", "openid profile pension:legal-approve")
        val product = humanToken("product-reviewer", "openid profile pension:product-approve")
        val missingScope = humanToken("legal-reviewer", "openid profile")
        assertThat(jwtPayload(legal).path("scope").asText().split(' ')).contains("pension:legal-approve")
        assertThat(jwtPayload(legal).path("realm_access").path("roles").map { it.asText() })
            .contains("ROLE_PENSION_LEGAL_COUNSEL")
        assertThat(jwtPayload(legal).path("preferred_username").asText()).isEqualTo("legal-reviewer")
        assertThat(jwtPayload(legal).path("sub").asText()).isNotBlank()
        assertThat(jwtPayload(product).path("scope").asText().split(' ')).contains("pension:product-approve")
        assertThat(jwtPayload(product).path("realm_access").path("roles").map { it.asText() })
            .contains("ROLE_PENSION_PRODUCT_OWNER")
        assertThat(jwtPayload(product).path("sub").asText()).isNotBlank()
        assertThat(jwtPayload(missingScope).path("scope").asText().split(' '))
            .doesNotContain("pension:legal-approve")

        approve(path, "LEGAL_COUNSEL", legal, 201)
        approve(path, "PRODUCT_OWNER", legal, 403)
        approve(path, "PRODUCT_OWNER", product, 201)
        approve(path, "LEGAL_COUNSEL", product, 403)
        approve(path, "LEGAL_COUNSEL", missingScope, 403)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT role, issuer, subject FROM pension_revision_approvals WHERE revision_id = ? ORDER BY role",
            ).use { statement ->
                statement.setObject(1, revisionId)
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    assertThat(rows.getString("role")).isEqualTo("LEGAL_COUNSEL")
                    assertThat(rows.getString("issuer")).isEqualTo(issuer)
                    assertThat(rows.getString("subject")).isEqualTo(jwtPayload(legal).path("sub").asText())
                    check(rows.next())
                    assertThat(rows.getString("role")).isEqualTo("PRODUCT_OWNER")
                    assertThat(rows.getString("issuer")).isEqualTo(issuer)
                    assertThat(rows.getString("subject")).isEqualTo(jwtPayload(product).path("sub").asText())
                    assertThat(rows.next()).isFalse()
                }
            }
        }
    }

    private fun approve(path: String, role: String, token: String, expected: Int) {
        given().auth().oauth2(token).contentType("application/json").header("If-Match", "\"0\"")
            .body("""{"reason":"synthetic review"}""")
            .post("$path/$role").then().statusCode(expected)
    }

    private fun jwtPayload(token: String) = ObjectMapper().readTree(Base64.getUrlDecoder().decode(token.split('.')[1]))

    private fun serviceToken(client: String): String = given().contentType("application/x-www-form-urlencoded")
        .formParam("grant_type", "client_credentials")
        .formParam("client_id", client)
        .formParam("client_secret", secret)
        .post("$issuer/protocol/openid-connect/token").then().statusCode(200)
        .extract().path("access_token")

    private fun humanToken(user: String, scopes: String): String = given()
        .contentType("application/x-www-form-urlencoded")
        .formParam("grant_type", "password")
        .formParam("client_id", "openbank-admin-ui")
        .formParam("client_secret", secret)
        .formParam("username", user)
        .formParam("password", secret)
        .formParam("scope", scopes)
        .post("$issuer/protocol/openid-connect/token").then().log().ifValidationFails().statusCode(200)
        .extract().path("access_token")

    private companion object {
        var seededOfferingId: UUID? = null
        var seededRevisionId: UUID? = null
    }
}

class CatalogPensionOidcApprovalProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.oidc.enabled" to "true",
        "quarkus.oidc.tenant-enabled" to "true",
        "authz.enforce" to "false",
    )
}
