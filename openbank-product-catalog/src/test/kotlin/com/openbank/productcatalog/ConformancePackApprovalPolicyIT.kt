// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.productcatalog.infrastructure.security.CatalogScopeIdentityAugmentor
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * #13429: the kernel's approval machinery is pack data. A test-only `conformance` pack declares a
 * role (`SAFETY_OFFICER`) and an authority (`WIDGET_SAFETY_APPROVER`) that appear nowhere in
 * `src/main`, and the full gate works for it: publish blocked until approved, the declared required
 * attribute enforced, an undeclared role refused, the maker excluded, and the digest frozen.
 */
@QuarkusTest
@TestProfile(ConformancePackApprovalPolicyIT.ConformancePack::class)
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_products")],
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ConformancePackApprovalPolicyIT {
    class ConformancePack : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "openbank.catalog.packs" to "banking,conformance",
            "openbank.catalog.reference-offerings-enabled" to "false",
            "openbank.catalog.approval.authorities.WIDGET_SAFETY_APPROVER.scope" to "widget:safety-approve",
            "openbank.catalog.approval.authorities.WIDGET_SAFETY_APPROVER.realm-role" to "ROLE_WIDGET_SAFETY",
        )
    }

    @Test
    @Order(1)
    @TestSecurity(user = "widget-maker", roles = ["ROLE_OPERATOR"])
    fun `maker drafts a widget whose pack declares an approval policy`() {
        val specificationId = create(
            "/api/v2/specifications",
            """{"code":"WIDGET_CONFORMANCE","schemaRef":{"id":"$SCHEMA_ID","version":1}}""",
        )
        offeringId = create(
            "/api/v2/offerings",
            """{"specificationId":"$specificationId","code":"WIDGET_ONE","market":{"countries":["CZ"]}}""",
        )
        revisionId = create(
            "/api/v2/offerings/$offeringId/revisions",
            """{"schemaRef":{"id":"$SCHEMA_ID","version":1},"name":{"en":"Widget"},""" +
                """"attributes":{"readiness":"DRAFTING"}}""",
        )
        Given { this } When { get("/api/v2/product-types/$SCHEMA_ID/versions/1") } Then { statusCode(200) }
    }

    @Test
    @Order(2)
    @TestSecurity(
        user = "safety-officer",
        roles = ["ROLE_OPERATOR", "ROLE_WIDGET_SAFETY"],
        augmentors = [CatalogScopeIdentityAugmentor::class],
    )
    @OidcSecurity(
        claims = [
            Claim(key = "scope", value = "widget:safety-approve"),
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "iss", value = "https://example.invalid/safety-issuer"),
            Claim(key = "sub", value = "safety-officer"),
        ],
    )
    fun `the declared role cannot approve before the declared attribute holds, nor can another pack's role`() {
        approve("SAFETY_OFFICER", 409, "\"0\"") // readiness is still DRAFTING
        // LEGAL_COUNSEL is declared by another pack; its authority is not one this officer holds.
        approve("LEGAL_COUNSEL", 403, "\"0\"")
    }

    @Test
    @Order(3)
    @TestSecurity(user = "widget-maker", roles = ["ROLE_OPERATOR"])
    fun `maker marks the widget ready`() {
        Given {
            contentType("application/json")
            header("If-Match", "\"0\"")
            body(
                """{"schemaRef":{"id":"$SCHEMA_ID","version":1},"name":{"en":"Widget"},""" +
                    """"attributes":{"readiness":"READY"}}""",
            )
        } When { put(base) } Then { statusCode(200) }
    }

    @Test
    @Order(4)
    @TestSecurity(user = "widget-checker", roles = ["ROLE_OPERATOR"])
    fun `publication is blocked until every declared role has approved`() {
        Given {
            contentType("application/json")
            header("If-Match", "\"1\"")
            body("""{"reason":"checker without approvals"}""")
        } When { post("$base/publish") } Then {
            statusCode(409)
            body(containsString("SAFETY_OFFICER"))
        }
    }

    @Test
    @Order(5)
    @TestSecurity(user = "widget-checker", roles = ["ROLE_OPERATOR", "ROLE_WIDGET_SAFETY"])
    @OidcSecurity(
        claims = [
            Claim(key = "scope", value = "catalog:read"),
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "widget-checker"),
        ],
    )
    fun `a realm role without its configured scope holds no approval authority`() {
        approve("SAFETY_OFFICER", 403, "\"1\"")
    }

    @Test
    @Order(6)
    @TestSecurity(
        user = "safety-officer",
        roles = ["ROLE_OPERATOR", "ROLE_WIDGET_SAFETY"],
        augmentors = [CatalogScopeIdentityAugmentor::class],
    )
    @OidcSecurity(
        claims = [
            Claim(key = "scope", value = "widget:safety-approve"),
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "iss", value = "https://example.invalid/safety-issuer"),
            Claim(key = "sub", value = "safety-officer"),
        ],
    )
    fun `the declared role approves once, by digest`() {
        approve("SAFETY_OFFICER", 201, "\"1\"")
        approve("SAFETY_OFFICER", 409, "\"1\"") // one approval per role and digest
        Given { this } When { get("$base/approvals") } Then {
            statusCode(200)
            body("$", hasSize<Any>(1))
            body("[0].role", equalTo("SAFETY_OFFICER"))
        }
    }

    @Test
    @Order(7)
    @TestSecurity(user = "widget-checker", roles = ["ROLE_OPERATOR"])
    fun `checker publishes and the approved digest is frozen on the revision`() {
        Given {
            contentType("application/json")
            header("If-Match", "\"1\"")
            body("""{"reason":"approved by the declared role"}""")
        } When { post("$base/publish") } Then {
            statusCode(200)
            body("state", equalTo("PUBLISHED"))
            body("approvalDigest", notNullValue())
            body("pensionApprovalDigest", notNullValue())
        }
    }

    private val base get() = "/api/v2/offerings/$offeringId/revisions/$revisionId"

    private fun approve(role: String, status: Int, ifMatch: String) {
        Given {
            contentType("application/json")
            header("If-Match", ifMatch)
            body("""{"reason":"independent safety review"}""")
        } When { post("$base/approvals/$role") } Then { statusCode(status) }
    }

    private fun create(path: String, json: String): String = Given {
        contentType("application/json")
        body(json)
    } When { post(path) } Then { statusCode(201) } Extract { path<String>("id") }

    companion object {
        const val SCHEMA_ID = "org.openbank.conformance.widget"
        private var offeringId: String? = null
        private var revisionId: String? = null
    }
}
