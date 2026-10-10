// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Test
import java.util.UUID

/** Real client-credentials JWT through the HTTP resource at catalog's advisory OPA setting. */
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
        given().auth().oauth2(token("unrelated-service")).get(path).then().statusCode(403)
        given().auth().oauth2(token("openbank-pension")).get(path).then().statusCode(404)
    }

    private fun token(client: String): String = given().contentType("application/x-www-form-urlencoded")
        .formParam("grant_type", "client_credentials")
        .formParam("client_id", client)
        .formParam("client_secret", secret)
        .post("$issuer/protocol/openid-connect/token").then().statusCode(200)
        .extract().path("access_token")
}

class CatalogPensionOidcApprovalProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.oidc.enabled" to "true",
        "quarkus.oidc.tenant-enabled" to "true",
        "authz.enforce" to "false",
    )
}
