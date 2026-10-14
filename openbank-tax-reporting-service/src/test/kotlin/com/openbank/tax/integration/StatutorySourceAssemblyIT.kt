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
import io.restassured.response.Response
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real HTTP, reactive Postgres and Flyway proof for the additive source-aware command. */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_tax_reporting_pension_golden_it")],
)
@QuarkusTestResource(StatutoryReturnMessagingTestResource::class)
@QuarkusTestResource(PensionProvidersStub::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class StatutorySourceAssemblyIT {
    private val path = "/api/v1/statutory-returns"
    private val request = """
        {
          "catalogueId":"cz-pension-cnb",
          "returnCode":"PSP40-01",
          "entityId":"${PensionProvidersStub.COMPANY}",
          "period":"2011"
        }
    """.trimIndent()

    companion object {
        private lateinit var dividendId: String
        private lateinit var raceDividendId: String
    }

    private fun captureCount(): Int {
        val stubBase = ConfigProvider.getConfig().getValue("tax.source-test.stub-base", String::class.java)
        return given().get("$stubBase/__stats").then().statusCode(200).extract().asString().toInt()
    }

    @Test
    @Order(1)
    @TestSecurity(user = "maker", roles = ["ROLE_OPERATOR"])
    fun `operator proposes the company dividend input`() {
        dividendId = given().contentType("application/json")
            .body(
                """{"entityId":"${PensionProvidersStub.COMPANY}","fact":"DIVIDEND_PAID_OR_PLANNED",
                    "value":0,"effectiveFrom":"2011-04-30","reason":"annual update",
                    "evidence":"test annual decision"}""",
            ).post("/api/v1/corporate-register").then().statusCode(201).extract().path<String>("id")
    }

    @Test
    @Order(2)
    @TestSecurity(user = "checker", roles = ["ROLE_OPERATOR"])
    fun `independent operator approves the company dividend input`() {
        given().post("/api/v1/corporate-register/$dividendId/approve").then().statusCode(200)
    }

    @Test
    @Order(3)
    @TestSecurity(user = "maker", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(claims = [Claim(key = "iss", value = "tax-test-issuer"), Claim(key = "sub", value = "maker")])
    fun `exact retry returns durable source revision without calling provider again`() {
        val key = "tax-source-command-2011-year-001"
        val first = given().contentType("application/json").header("Idempotency-Key", key).body(request)
            .post("$path/source-assemblies").then().statusCode(200).extract().jsonPath()
        val id = first.getString("id")
        assertThat(first.getInt("contentHashVersion")).isEqualTo(2)
        assertThat(first.getString("participantCloseLineage.sha256"))
            .isEqualTo(PensionProvidersStub.SOURCE_SHA)
        assertThat(first.getMap<String, String>("participantCloseLineage.cellPaths")).hasSize(2)
        assertThat(first.getMap<String, Any>("$")).doesNotContainKey("fundCloseLineage")
        val captured = captureCount()
        val retry = given().contentType("application/json").header("Idempotency-Key", key).body(request)
            .post("$path/source-assemblies").then().statusCode(200).extract().jsonPath()
        assertThat(retry.getString("id")).isEqualTo(id)
        assertThat(retry.getString("contentHash")).isEqualTo(first.getString("contentHash"))
        assertThat(captureCount()).isEqualTo(captured)

        val stored = given().get("$path/$id").then().statusCode(200).extract().jsonPath()
        assertThat(stored.getString("participantCloseLineage.snapshotId"))
            .isEqualTo(PensionProvidersStub.SNAPSHOT_ID)
        assertThat(stored.getString("contentHash")).isEqualTo(first.getString("contentHash"))
        val v2Stored = given().get("/api/v2/statutory-returns/$id").then().statusCode(200).extract().jsonPath()
        assertThat(v2Stored.getInt("contentHashVersion")).isEqualTo(2)
        assertThat(v2Stored.getString("contentHash")).isEqualTo(first.getString("contentHash"))

        given().contentType("application/json").header("Idempotency-Key", key)
            .body(request.replace("2011", "2010"))
            .post("$path/source-assemblies").then().statusCode(409)
        assertThat(captureCount()).isEqualTo(captured)
    }

    @Test
    @Order(4)
    @TestSecurity(user = "maker", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(claims = [Claim(key = "iss", value = "tax-test-issuer"), Claim(key = "sub", value = "maker")])
    fun `missing source command body is a bad request without provider capture`() {
        val before = captureCount()
        given().contentType("application/json").header("Idempotency-Key", "missing-body-key-123456")
            .post("$path/source-assemblies").then().statusCode(400)
        assertThat(captureCount()).isEqualTo(before)
    }

    @Test
    @Order(5)
    @TestSecurity(user = "maker", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(claims = [Claim(key = "iss", value = "tax-test-issuer"), Claim(key = "sub", value = "maker")])
    fun `PSP31 refuses an unverified beneficiary stock without capture or Tax revision`() {
        val before = captureCount()
        given().contentType("application/json").header("Idempotency-Key", "psp31-unverified-key-123456")
            .body(request.replace("PSP40-01", "PSP31-04").replace("2011", "2012-Q1"))
            .post("$path/source-assemblies").then().statusCode(503)
        assertThat(captureCount()).isEqualTo(before)
        val returns = given().get(path).then().statusCode(200).extract().jsonPath()
            .getList<Map<String, Any>>("$")
        assertThat(returns.none { it["returnCode"] == "PSP31-04" && it["period"] == "2012-Q1" }).isTrue()
    }

    @Test
    @Order(6)
    @TestSecurity(user = "maker", roles = ["ROLE_OPERATOR"])
    fun `operator proposes independent 2013 dividend input for concurrent source capture`() {
        raceDividendId = given().contentType("application/json")
            .body(
                """{"entityId":"${PensionProvidersStub.COMPANY}","fact":"DIVIDEND_PAID_OR_PLANNED",
                    "value":0,"effectiveFrom":"2013-04-30","reason":"annual update",
                    "evidence":"test annual decision"}""",
            ).post("/api/v1/corporate-register").then().statusCode(201).extract().path<String>("id")
    }

    @Test
    @Order(7)
    @TestSecurity(user = "checker", roles = ["ROLE_OPERATOR"])
    fun `independent operator approves 2013 dividend input`() {
        given().post("/api/v1/corporate-register/$raceDividendId/approve").then().statusCode(200)
    }

    @Test
    @Order(8)
    @TestSecurity(user = "maker", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(claims = [Claim(key = "iss", value = "tax-test-issuer"), Claim(key = "sub", value = "maker")])
    fun `two simultaneous identical source commands commit one durable Tax revision`() {
        val raceRequest = request.replace("2011", "2013")
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val requests = (1..2).map {
                workers.submit<Response> {
                    start.await(5, TimeUnit.SECONDS)
                    given().contentType("application/json")
                        .header("Idempotency-Key", "tax-source-concurrent-2013-year-001")
                        .body(raceRequest).post("$path/source-assemblies")
                }
            }
            start.countDown()
            val responses = requests.map { it.get(30, TimeUnit.SECONDS) }
            assertThat(responses.map { it.statusCode }).containsExactly(200, 200)
            val revisions = responses.map { it.jsonPath().getString("id") }
            assertThat(revisions[0]).isEqualTo(revisions[1])
            val stored = given().get(path).then().statusCode(200).extract().jsonPath()
                .getList<Map<String, Any>>("$")
            assertThat(stored.filter { it["returnCode"] == "PSP40-01" && it["period"] == "2013" })
                .hasSize(1)
        } finally {
            workers.shutdownNow()
        }
    }
}
