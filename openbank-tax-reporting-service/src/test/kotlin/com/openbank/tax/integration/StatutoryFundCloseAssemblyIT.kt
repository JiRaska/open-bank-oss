// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/** Real HTTP and Postgres proof of the additive exact sealed-fund command and V5 persistence. */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_tax_reporting_pension_golden_it")],
)
@QuarkusTestResource(StatutoryReturnMessagingTestResource::class)
@QuarkusTestResource(PensionProvidersStub::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class StatutoryFundCloseAssemblyIT {
    private val path = "/api/v2/statutory-returns/source-assemblies"
    private val v2 = "/api/v2/statutory-returns"
    private val v1 = "/api/v1/statutory-returns"
    private val request = """{
        "catalogueId":"cz-pension-cnb", "returnCode":"PEF15-01",
        "entityId":"${PensionProvidersStub.FUND}", "period":"2014",
        "fundCloseId":"${PensionProvidersStub.FUND_CLOSE_ID}"
    }
    """.trimIndent()

    private fun closeReads(): Int {
        val stubBase = org.eclipse.microprofile.config.ConfigProvider.getConfig()
            .getValue("tax.source-test.stub-base", String::class.java)
        return given().get("$stubBase/__fund-stats").then().statusCode(200).extract().asString().toInt()
    }

    @Test
    @Order(0)
    @TestSecurity(user = "maker", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(claims = [Claim(key = "iss", value = "tax-test-issuer"), Claim(key = "sub", value = "maker")])
    fun `tampered selected close is unavailable and creates no Tax revision`() {
        given().contentType("application/json").header("Idempotency-Key", "fund-bad-source-2014-year-001")
            .body(request.replace(PensionProvidersStub.FUND_CLOSE_ID, PensionProvidersStub.BAD_FUND_CLOSE_ID))
            .post(path).then().statusCode(503)
        val rows = given().get(v2).then().statusCode(200).extract().jsonPath()
            .getList<Map<String, Any>>("$")
        assertThat(rows.none { it["returnCode"] == "PEF15-01" && it["period"] == "2014" }).isTrue()
    }

    companion object {
        private lateinit var assembledId: String
    }

    @Test
    @Order(1)
    @TestSecurity(user = "maker", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(claims = [Claim(key = "iss", value = "tax-test-issuer"), Claim(key = "sub", value = "maker")])
    fun `selected close persists v3 lineage and exact key replay without a second provider read`() {
        val key = "fund-close-command-2014-year-001"
        val before = closeReads()
        val first = given().contentType("application/json").header("Idempotency-Key", key).body(request)
            .post(path).then().statusCode(200).extract().jsonPath()
        val id = first.getString("id")
        assembledId = id
        assertThat(first.getInt("contentHashVersion")).isEqualTo(3)
        assertThat(first.getString("fundCloseLineage.closeId")).isEqualTo(PensionProvidersStub.FUND_CLOSE_ID)
        assertThat(first.getString("fundCloseLineage.sha256")).isEqualTo(PensionProvidersStub.FUND_CLOSE_SHA)
        assertThat(first.getMap<String, String>("fundCloseLineage.cellPaths"))
            .containsEntry("participants_count", "/participants/holders")
        assertThat(closeReads()).isEqualTo(before + 1)

        val retry = given().contentType("application/json").header("Idempotency-Key", key).body(request)
            .post(path).then().statusCode(200).extract().jsonPath()
        assertThat(retry.getString("id")).isEqualTo(id)
        assertThat(retry.getString("contentHash")).isEqualTo(first.getString("contentHash"))
        assertThat(closeReads()).isEqualTo(before + 1)

        val stored = given().get("$v2/$id").then().statusCode(200).extract().jsonPath()
        assertThat(stored.getString("fundCloseLineage.closeId")).isEqualTo(PensionProvidersStub.FUND_CLOSE_ID)
        assertThat(stored.getString("contentHash")).isEqualTo(first.getString("contentHash"))
        val rows = given().get(v2).then().statusCode(200).extract().jsonPath()
            .getList<Map<String, Any>>("$")
        assertThat(rows.filter { it["returnCode"] == "PEF15-01" && it["period"] == "2014" }).hasSize(1)
        given().get("$v1/capability").then().statusCode(200).header("X-API-Version", "v1")
        given().get("$v2/capability").then().statusCode(200).header("X-API-Version", "v2")
        val legacyRows = given().get(v1).then().statusCode(200).extract().jsonPath()
            .getList<Map<String, Any>>("$")
        assertThat(legacyRows.none { it["id"] == id }).isTrue()
        given().get("$v1/$id").then().statusCode(404)
        given().post("$v1/$id/approve").then().statusCode(404)
        given().contentType("application/json").body("""{"reference":"TEST"}""")
            .post("$v1/$id/submitted").then().statusCode(404)

        given().contentType("application/json").header("Idempotency-Key", key)
            .body(request.replace(PensionProvidersStub.FUND_CLOSE_ID, "d53c2305-0875-4e7a-a245-c24516ca0d76"))
            .post(path).then().statusCode(409)
        assertThat(closeReads()).isEqualTo(before + 1)
    }

    @Test
    @Order(2)
    @TestSecurity(user = "maker", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(claims = [Claim(key = "iss", value = "tax-test-issuer"), Claim(key = "sub", value = "maker")])
    fun `fund command without selected close and participant command with one both fail before source read`() {
        val before = closeReads()
        val missingClose = request.replace(
            Regex(",\\s*\"fundCloseId\":\"[^\"]+\""),
            "",
        )
        given().contentType("application/json").header("Idempotency-Key", "fund-missing-close-2014-year")
            .body(missingClose)
            .post(path).then().statusCode(400)
        given().contentType("application/json").header("Idempotency-Key", "participant-spurious-close-2014")
            .body(
                request.replace(
                    "PEF15-01",
                    "PSP40-01",
                ).replace(PensionProvidersStub.FUND, PensionProvidersStub.COMPANY),
            )
            .post(path).then().statusCode(400)
        given().contentType("application/json").header("Idempotency-Key", "legacy-spurious-close-2014")
            .body(
                request.replace(
                    "PEF15-01",
                    "PSP40-01",
                ).replace(PensionProvidersStub.FUND, PensionProvidersStub.COMPANY),
            )
            .post("$v1/source-assemblies").then().statusCode(400)
        assertThat(closeReads()).isEqualTo(before)
    }

    @Test
    @Order(3)
    @TestSecurity(user = "checker", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(claims = [Claim(key = "iss", value = "tax-test-issuer"), Claim(key = "sub", value = "checker")])
    fun `v2 independent checker can approve selected sealed close`() {
        val approved = given().post("$v2/$assembledId/approve").then().statusCode(200).extract().jsonPath()
        assertThat(approved.getString("status")).isEqualTo("APPROVED")
        assertThat(approved.getString("fundCloseLineage.closeId")).isEqualTo(PensionProvidersStub.FUND_CLOSE_ID)
        given().post("$v1/$assembledId/approve").then().statusCode(404)
    }

    @Test
    @Order(4)
    @TestSecurity(user = "checker", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(claims = [Claim(key = "iss", value = "tax-test-issuer"), Claim(key = "sub", value = "checker")])
    fun `v2 records submission while v1 still refuses the v3 revision`() {
        val submitted = given().contentType("application/json").body("""{"reference":"TEST-2014-CLOSE"}""")
            .post("$v2/$assembledId/submitted").then().statusCode(200).extract().jsonPath()
        assertThat(submitted.getString("status")).isEqualTo("SUBMITTED")
        assertThat(submitted.getString("fundCloseLineage.closeId")).isEqualTo(PensionProvidersStub.FUND_CLOSE_ID)
        given().get("$v1/$assembledId").then().statusCode(404)
    }
}
