// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.productcatalog.infrastructure.security.CatalogScopeIdentityAugmentor
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * ADR-0352 §7 (phase 5b) on the ROLE approval path (`POST .../approvals/{role}`): a revision that
 * carries product IR is approvable only by a principal (JWT issuer + subject) that ran the
 * server-defined GOLDEN backtest of the revision as it stands now. Each way of not having seen the
 * change is a 409: no backtest, another principal's, a backtest over a portfolio the approver chose,
 * and one taken before the draft was edited. Same conformance pack as ConformancePackApprovalPolicyIT.
 */
@QuarkusTest
@TestProfile(ConformancePackApprovalPolicyIT.ConformancePack::class)
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_products")],
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IrRoleApprovalBacktestIT {

    @Test
    @Order(1)
    @TestSecurity(user = "widget-maker", roles = ["ROLE_OPERATOR"])
    fun `maker drafts a ready widget that carries product IR`() {
        val specificationId = create(
            "/api/v2/specifications",
            """{"code":"WIDGET_IR_BACKTEST","schemaRef":{"id":"$SCHEMA_ID","version":1}}""",
        )
        offeringId = create(
            "/api/v2/offerings",
            """{"specificationId":"$specificationId","code":"WIDGET_IR_ONE","market":{"countries":["CZ"]}}""",
        )
        revisionId = create("/api/v2/offerings/$offeringId/revisions", revisionJson("READY"))
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
            Claim(
                key = "scope",
                value = "widget:safety-approve",
            ), Claim(
                key = "azp",
                value = "openbank-admin-ui",
            ), Claim(key = "iss", value = ISSUER), Claim(key = "sub", value = "safety-officer"),
        ],
    )
    fun `no backtest - the role approval is a 409`() {
        approve(409, "\"0\"")
    }

    @Test
    @Order(3)
    @TestSecurity(user = "someone-else", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(
                key = "azp",
                value = "openbank-admin-ui",
            ), Claim(key = "iss", value = ISSUER), Claim(key = "sub", value = "someone-else"),
        ],
    )
    fun `another principal runs the golden backtest`() {
        backtest(golden = true, approvalEvidence = true)
    }

    @Test
    @Order(4)
    @TestSecurity(
        user = "safety-officer",
        roles = ["ROLE_OPERATOR", "ROLE_WIDGET_SAFETY"],
        augmentors = [CatalogScopeIdentityAugmentor::class],
    )
    @OidcSecurity(
        claims = [
            Claim(
                key = "scope",
                value = "widget:safety-approve",
            ), Claim(
                key = "azp",
                value = "openbank-admin-ui",
            ), Claim(key = "iss", value = ISSUER), Claim(key = "sub", value = "safety-officer"),
        ],
    )
    fun `another principal's backtest and the approver's own CUSTOM portfolio do not unlock it`() {
        approve(409, "\"0\"")
        backtest(golden = false, approvalEvidence = false)
        approve(409, "\"0\"")
    }

    @Test
    @Order(5)
    @TestSecurity(
        user = "safety-officer",
        roles = ["ROLE_OPERATOR", "ROLE_WIDGET_SAFETY"],
        augmentors = [CatalogScopeIdentityAugmentor::class],
    )
    @OidcSecurity(
        claims = [
            Claim(
                key = "scope",
                value = "widget:safety-approve",
            ), Claim(
                key = "azp",
                value = "openbank-admin-ui",
            ), Claim(key = "iss", value = ISSUER), Claim(key = "sub", value = "safety-officer"),
        ],
    )
    fun `the approver's golden backtest is recorded`() {
        backtest(golden = true, approvalEvidence = true)
    }

    @Test
    @Order(6)
    @TestSecurity(user = "widget-maker", roles = ["ROLE_OPERATOR"])
    fun `maker edits the draft after the backtest`() {
        Given {
            contentType("application/json")
            header("If-Match", "\"0\"")
            body(revisionJson("READY"))
        } When { put(base) } Then { statusCode(200) }
    }

    @Test
    @Order(7)
    @TestSecurity(
        user = "safety-officer",
        roles = ["ROLE_OPERATOR", "ROLE_WIDGET_SAFETY"],
        augmentors = [CatalogScopeIdentityAugmentor::class],
    )
    @OidcSecurity(
        claims = [
            Claim(
                key = "scope",
                value = "widget:safety-approve",
            ), Claim(
                key = "azp",
                value = "openbank-admin-ui",
            ), Claim(key = "iss", value = ISSUER), Claim(key = "sub", value = "safety-officer"),
        ],
    )
    fun `a backtest of the content before the edit does not unlock it, a fresh golden one does`() {
        approve(409, "\"1\"")
        backtest(golden = true, approvalEvidence = true)
        approve(201, "\"1\"")
    }

    private val base get() = "/api/v2/offerings/$offeringId/revisions/$revisionId"

    private fun approve(status: Int, ifMatch: String) {
        Given {
            contentType("application/json")
            header("If-Match", ifMatch)
            body("""{"reason":"independent safety review"}""")
        } When { post("$base/approvals/SAFETY_OFFICER") } Then { statusCode(status) }
    }

    /** Golden: no portfolio, so the server's own; custom: the approver's. Nothing is published yet, so the base is itself. */
    private fun backtest(golden: Boolean, approvalEvidence: Boolean) {
        val portfolio = if (golden) "" else ""","portfolio":$CUSTOM_PORTFOLIO"""
        Given {
            contentType("application/json")
            body("""{"baseRevisionId":"$revisionId"$portfolio}""")
        } When { post("$base/behaviour-diff") } Then {
            statusCode(200)
            body("approvalEvidence", equalTo(approvalEvidence))
        }
    }

    private fun revisionJson(readiness: String) =
        """{"schemaRef":{"id":"$SCHEMA_ID","version":1},"name":{"en":"Widget"},""" +
            """"attributes":{"readiness":"$readiness"},"behaviour":$IR}"""

    private fun create(path: String, json: String): String = Given {
        contentType("application/json")
        body(json)
    } When { post(path) } Then { statusCode(201) } Extract { path<String>("id") }

    companion object {
        const val SCHEMA_ID = "org.openbank.conformance.widget"
        const val ISSUER = "https://example.invalid/safety-issuer"
        const val CUSTOM_PORTFOLIO =
            """{"accounts":[{"id":"mine","state":{"currency":"EUR","balance":"1.00","openedOn":"2026-01-01"}}]}"""
        val IR: String = Files.readString(Path.of("..", "openbank-products", "ir", "savings-fixed.json")).trimEnd()
        private var offeringId: String? = null
        private var revisionId: String? = null
    }
}
