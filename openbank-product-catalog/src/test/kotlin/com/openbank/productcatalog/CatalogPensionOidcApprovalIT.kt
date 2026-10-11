// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID

/** Real service and human JWTs through the HTTP resource at catalog's advisory OPA setting. */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_catalog_pension_oidc")],
)
@QuarkusTestResource(CatalogPensionOidcTestResource::class, restrictToAnnotatedClass = true)
@TestProfile(CatalogPensionOidcApprovalProfile::class)
class CatalogPensionOidcApprovalIT {
    @ConfigProperty(name = "openbank.test.pension.issuer")
    lateinit var issuer: String

    @ConfigProperty(name = "openbank.test.pension.secret")
    lateinit var secret: String

    @Test
    fun `pension service JWT gains scoped read and unrelated service JWT is forbidden`() {
        val path = "/api/v2/offerings/${UUID.randomUUID()}/revisions/${UUID.randomUUID()}/pension-approvals"
        given().get(path).then().statusCode(401)
        given().auth().oauth2(serviceToken("unrelated-service")).get(path).then().statusCode(403)
        given().auth().oauth2(serviceToken("openbank-pension")).get(path).then().statusCode(404)
    }

    @Test
    fun `real legal and product JWTs pass only their own approval role with requested scope`() {
        val path = "/api/v2/offerings/${UUID.randomUUID()}/revisions/${UUID.randomUUID()}/pension-approvals"
        val legal = humanToken("legal-reviewer", "openid profile pension:legal-approve")
        val product = humanToken("product-reviewer", "openid profile pension:product-approve")
        val missingScope = humanToken("legal-reviewer", "openid profile")
        assertThat(jwtPayload(legal).path("scope").asText().split(' ')).contains("pension:legal-approve")
        assertThat(jwtPayload(legal).path("realm_access").path("roles").map { it.asText() })
            .contains("ROLE_PENSION_LEGAL_COUNSEL")
        assertThat(jwtPayload(legal).path("preferred_username").asText()).isEqualTo("legal-reviewer")
        assertThat(jwtPayload(product).path("scope").asText().split(' ')).contains("pension:product-approve")
        assertThat(jwtPayload(product).path("realm_access").path("roles").map { it.asText() })
            .contains("ROLE_PENSION_PRODUCT_OWNER")
        assertThat(jwtPayload(missingScope).path("scope").asText().split(' '))
            .doesNotContain("pension:legal-approve")

        approve(path, "LEGAL_COUNSEL", legal, 404)
        approve(path, "PRODUCT_OWNER", legal, 403)
        approve(path, "PRODUCT_OWNER", product, 404)
        approve(path, "LEGAL_COUNSEL", product, 403)
        approve(path, "LEGAL_COUNSEL", missingScope, 403)
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
}

class CatalogPensionOidcApprovalProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.oidc.enabled" to "true",
        "quarkus.oidc.tenant-enabled" to "true",
        "authz.enforce" to "false",
    )
}
