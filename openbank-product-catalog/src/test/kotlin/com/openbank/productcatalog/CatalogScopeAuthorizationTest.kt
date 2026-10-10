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
    @TestSecurity(
        user = "pension-legal",
        roles = ["ROLE_PENSION_LEGAL_COUNSEL"],
        augmentors = [CatalogScopeIdentityAugmentor::class],
    )
    @OidcSecurity(claims = [Claim(key = "scope", value = "openid")])
    fun pensionLegalRealmRoleWithoutApprovalScopeCannotApprove() {
        val base = "/api/v2/offerings/00000000-0000-0000-0000-000000000001" +
            "/revisions/00000000-0000-0000-0000-000000000002/pension-approvals"
        given().contentType("application/json").header("If-Match", "\"0\"")
            .body("""{"reason":"review"}""")
            .post("$base/PRODUCT_OWNER").then().statusCode(403)
        given().contentType("application/json").header("If-Match", "\"0\"")
            .body("""{"reason":"review"}""")
            .post("$base/LEGAL_COUNSEL").then().statusCode(403)
    }

    @Test
    @TestSecurity(
        user = "pension-legal",
        roles = ["ROLE_PENSION_LEGAL_COUNSEL"],
        augmentors = [CatalogScopeIdentityAugmentor::class],
    )
    @OidcSecurity(claims = [Claim(key = "scope", value = "pension:legal-approve")])
    fun pensionLegalRoleAndScopeAllowOnlyLegalApproval() {
        val base = "/api/v2/offerings/00000000-0000-0000-0000-000000000001" +
            "/revisions/00000000-0000-0000-0000-000000000002/pension-approvals"
        given().contentType("application/json").header("If-Match", "\"0\"")
            .body("""{"reason":"review"}""")
            .post("$base/PRODUCT_OWNER").then().statusCode(403)
        given().contentType("application/json").header("If-Match", "\"0\"")
            .body("""{"reason":"review"}""")
            .post("$base/LEGAL_COUNSEL").then().statusCode(404)
    }

    @Test
    @TestSecurity(
        user = "pension-unverified-internal-role",
        roles = ["PENSION_LEGAL_APPROVER"],
        augmentors = [CatalogScopeIdentityAugmentor::class],
    )
    @OidcSecurity(claims = [Claim(key = "scope", value = "pension:legal-approve")])
    fun rawInternalRoleWithoutNamedRealmAssignmentCannotApprove() {
        given().contentType("application/json").header("If-Match", "\"0\"")
            .body("""{"reason":"review"}""")
            .post(
                "/api/v2/offerings/00000000-0000-0000-0000-000000000001" +
                    "/revisions/00000000-0000-0000-0000-000000000002/pension-approvals/LEGAL_COUNSEL",
            ).then().statusCode(403)
    }

    @Test
    @TestSecurity(
        user = "pension-legal",
        roles = ["ROLE_PENSION_LEGAL_COUNSEL"],
        augmentors = [CatalogScopeIdentityAugmentor::class],
    )
    @OidcSecurity(claims = [Claim(key = "scope", value = "pension:legal-approve")])
    fun namedHumanLegalRoleAndScopeDeriveCatalogRead() {
        given().get("/api/v2/offerings").then().statusCode(200)
        given().get(
            "/api/v2/offerings/00000000-0000-0000-0000-000000000001" +
                "/revisions/00000000-0000-0000-0000-000000000002/pension-approvals",
        ).then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "pension-scope-only", augmentors = [CatalogScopeIdentityAugmentor::class])
    @OidcSecurity(claims = [Claim(key = "scope", value = "pension:legal-approve")])
    fun approvalScopeWithoutRealmRoleCannotDecide() {
        given().contentType("application/json").header("If-Match", "\"0\"")
            .body("""{"reason":"review"}""")
            .post(
                "/api/v2/offerings/00000000-0000-0000-0000-000000000001" +
                    "/revisions/00000000-0000-0000-0000-000000000002/pension-approvals/LEGAL_COUNSEL",
            ).then().statusCode(403)
    }

    @Test
    @TestSecurity(
        user = "service-account-other",
        roles = ["ROLE_PENSION_LEGAL_COUNSEL"],
        augmentors = [CatalogScopeIdentityAugmentor::class],
    )
    @OidcSecurity(claims = [Claim(key = "scope", value = "openid")])
    fun machineWithAccidentallyAssignedReviewerRoleCannotReadOrDecide() {
        given().get("/api/v2/offerings").then().statusCode(403)
        given().contentType("application/json").header("If-Match", "\"0\"")
            .body("""{"reason":"review"}""")
            .post(
                "/api/v2/offerings/00000000-0000-0000-0000-000000000001" +
                    "/revisions/00000000-0000-0000-0000-000000000002/pension-approvals/LEGAL_COUNSEL",
            ).then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "pension-catalog-reader", augmentors = [CatalogScopeIdentityAugmentor::class])
    @OidcSecurity(claims = [Claim(key = "scope", value = "catalog:read")])
    fun readScopeAllowsV2ReadsButNotAuthoring() {
        given().get("/api/v2/offerings").then().statusCode(200)
        given().get("/api/v2/products/00000000-0000-0000-0000-000000000001")
            .then().statusCode(404)
        given().get(
            "/api/v2/offerings/00000000-0000-0000-0000-000000000001" +
                "/revisions/00000000-0000-0000-0000-000000000002/pension-approvals",
        ).then().statusCode(404)
        given().contentType("application/json")
            .body(
                """
                {"code":"PENSION_READ_ONLY","schemaRef":{
                    "id":"org.openbank.insurance.term-life","version":1}}
                """.trimIndent(),
            )
            .post("/api/v2/specifications").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-pension", augmentors = [CatalogScopeIdentityAugmentor::class])
    @OidcSecurity(claims = [Claim(key = "scope", value = "catalog:read")])
    fun pensionM2mScopePassesTheThreeCatalogReadRoleChecksOnly() {
        given().get("/api/v2/offerings").then().statusCode(200)
        given().get("/api/v2/products/00000000-0000-0000-0000-000000000001")
            .then().statusCode(404)
        given().get(
            "/api/v2/offerings/00000000-0000-0000-0000-000000000001" +
                "/revisions/00000000-0000-0000-0000-000000000002/pension-approvals",
        ).then().statusCode(404)
        given().contentType("application/json")
            .body("""{"code":"PENSION_M2M_DENIED","schemaRef":{"id":"org.openbank.insurance.term-life","version":1}}""")
            .post("/api/v2/specifications").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-pension", augmentors = [CatalogScopeIdentityAugmentor::class])
    @OidcSecurity(claims = [Claim(key = "scope", value = "openid")])
    fun pensionM2mWithoutCatalogReadScopeCannotListOfferings() {
        given().get("/api/v2/offerings").then().statusCode(403)
    }

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
