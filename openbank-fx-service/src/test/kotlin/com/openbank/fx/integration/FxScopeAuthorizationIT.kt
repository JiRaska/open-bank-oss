// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.integration

import com.openbank.fx.infrastructure.security.FxScopeIdentityAugmentor
import com.openbank.fx.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Test

/**
 * The fx-rates-read synthetic journey's identity boundary (ADR-0252, mirrors
 * product-catalog's CatalogScopeAuthorizationTest, #7324).
 *
 * `openbank-synthetic-fx-read` holds NO realm role: the only way its token passes
 * FxResource's RBAC outer gate is the FxScopeIdentityAugmentor deriving FX_SCOPE_READ
 * from the `fx:read` client scope. This test drives the real HTTP resource with a token
 * carrying exactly that claim shape — and a negative token without it — so the augmentor
 * cannot be deleted or bypassed while the journey stays green.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class FxScopeAuthorizationIT {

    @Test
    @TestSecurity(user = "synthetic-fx-read", augmentors = [FxScopeIdentityAugmentor::class])
    @OidcSecurity(claims = [Claim(key = "scope", value = "fx:read")])
    fun authorizesFxReadScopeWithoutRealmRoles() {
        // Both endpoints exercise the augmentor and RBAC with only the scope-derived role.
        // OPA is advisory in this test; separate Rego tests cover policy logic.
        given().get("/api/v1/fx/rates").then().statusCode(200)
        given().get("/api/v1/fx/rates/EUR/CZK").then().statusCode(200)
    }

    @Test
    @TestSecurity(user = "synthetic-fx-read-no-scope", augmentors = [FxScopeIdentityAugmentor::class])
    @OidcSecurity(claims = [Claim(key = "scope", value = "openid profile")])
    fun deniesTokenWithoutFxReadScope() {
        given().get("/api/v1/fx/rates").then().statusCode(403)
    }
}
