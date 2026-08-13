// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.productcatalog.infrastructure.security.CatalogScopeIdentityAugmentor
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Test

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_products_scope")],
)
class CatalogScopeAuthorizationTest {
    @Test
    @TestSecurity(user = "external-catalog-author", augmentors = [CatalogScopeIdentityAugmentor::class])
    @OidcSecurity(claims = [Claim(key = "scope", value = "catalog:read catalog:author")])
    fun authorizesProviderNeutralOidcScopesWithoutOpenBankRoles() {
        given().get("/api/v2/product-types").then().statusCode(200)
        given().contentType("application/json")
            .body(
                """
                {"code":"INS_SCOPE_AUTH","schemaRef":{
                    "id":"org.openbank.insurance.term-life","version":1}}
                """.trimIndent(),
            )
            .post("/api/v2/specifications").then().statusCode(201)
    }
}
