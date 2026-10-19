// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.pensionfund.application.port.FundTermsPort
import com.openbank.pensionfund.application.port.PinnedFundTerms
import com.openbank.pensionfund.infrastructure.catalog.FundRevisionTerms
import com.openbank.pensionfund.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(FundPinnedFeePrecisionIT.PrecisionProfile::class)
@TestSecurity(user = "precision-operator", roles = ["ROLE_OPERATOR"])
class FundPinnedFeePrecisionIT {
    class PrecisionProfile : QuarkusTestProfile {
        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(PrecisionCatalogTerms::class.java)
    }

    @Test
    fun `published revision fee retains eight fractional digits after persistence and prices a real NAV`() {
        val isin = "PF" + UUID.randomUUID().toString().replace("-", "").take(9).uppercase() + "0"
        val fund = UUID.fromString(
            given().contentType("application/json")
                .body(
                    """{"name":"Precision fixture","isin":"$isin","lei":"315700ABCDEF12345678",
                        "depositaryReference":"TEST-DEP","custodyAccountReference":"TEST-CUST",
                        "currency":"CZK","riskClass":3,"mandatoryConservative":false,
                        "productRevisionId":"${PrecisionCatalogTerms.REVISION}","launchNavPerUnit":1}
                    """.trimIndent(),
                ).post("/api/v2/funds").then().statusCode(201).extract().path<String>("id"),
        )

        connection().use { db ->
            // Prove this value discriminates the old schema using Postgres's own numeric coercion.
            db.prepareStatement("SELECT CAST(? AS NUMERIC(9,6))").use { st ->
                st.setBigDecimal(1, BigDecimal("0.00123456"))
                st.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getBigDecimal(1)).isEqualByComparingTo("0.001235")
                }
            }
            db.prepareStatement("SELECT product_revision_id, management_fee_rate FROM funds WHERE id = ?").use { st ->
                st.setObject(1, fund)
                st.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getObject(1, UUID::class.java)).isEqualTo(PrecisionCatalogTerms.REVISION)
                    assertThat(rows.getBigDecimal(2)).isEqualByComparingTo("0.00123456")
                    assertThat(rows.next()).isFalse()
                }
            }
        }

        // A separate HTTP request makes NavService load the fund from the real store again.
        // One day on CZK 365,000,000 costs 1,234.56; the old NUMERIC(9,6) rate costs 1,235.00.
        val nav = UUID.fromString(
            given().contentType("application/json")
                .body("""{"valuationDate":"${LocalDate.now(ZoneOffset.UTC)}","cash":365000000}""")
                .post("/api/v2/funds/$fund/navs").then().statusCode(201).extract().path<String>("id"),
        )
        connection().use { db ->
            db.prepareStatement(
                "SELECT gross_assets, accrued_management_fee, net_assets FROM fund_navs WHERE id = ? AND fund_id = ?",
            ).use { st ->
                st.setObject(1, nav)
                st.setObject(2, fund)
                st.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getBigDecimal(1)).isEqualByComparingTo("365000000.00")
                    assertThat(rows.getBigDecimal(2)).isEqualByComparingTo("1234.56")
                    assertThat(rows.getBigDecimal(3)).isEqualByComparingTo("364998765.44")
                    assertThat(rows.next()).isFalse()
                }
            }
        }
    }

    @Test
    fun `v2 creation requires an explicit revision and amendments retain the stored pin`() {
        val body = definitionBody()
        val withoutRevision = body.filterKeys { it != "productRevisionId" }
        given().contentType("application/json").body(withoutRevision)
            .post("/api/v2/funds").then().statusCode(400)
        given().contentType("application/json").body(body + ("productRevisionId" to null))
            .post("/api/v2/funds").then().statusCode(400)
        given().contentType("application/json").body(body + ("productRevisionId" to UUID.randomUUID().toString()))
            .post("/api/v2/funds").then().statusCode(400)
        val id = given().contentType("application/json").body(body)
            .post("/api/v2/funds").then().statusCode(201).extract().path<String>("id")
        given().contentType("application/json").body(withoutRevision)
            .put("/api/v2/funds/$id").then().statusCode(200)
        given().contentType("application/json").body(withoutRevision + ("productRevisionId" to null))
            .put("/api/v2/funds/$id").then().statusCode(200)
        given().contentType("application/json").body(body)
            .put("/api/v2/funds/$id").then().statusCode(200)
        given().contentType("application/json").body(body + ("productRevisionId" to UUID.randomUUID().toString()))
            .put("/api/v2/funds/$id").then().statusCode(400)
        given().contentType("application/json").body(withoutRevision + ("managementFeeRate" to BigDecimal("0.5")))
            .put("/api/v2/funds/$id").then().statusCode(400)
        val stored = given().get("/api/v2/funds/$id").then().statusCode(200).extract().jsonPath()
        assertThat(stored.getString("productRevisionId")).isEqualTo(PrecisionCatalogTerms.REVISION.toString())
        assertThat(stored.getString("managementFeeRate")).isEqualTo("0.00123456")
    }

    @Test
    fun `running v2 routes and create amendment schemas agree with the configured major`() {
        assertThat(ConfigProvider.getConfig().getValue("openbank.api.version", String::class.java)).isEqualTo("2")
        given().get("/api/v2/funds").then().statusCode(200).header("X-API-Version", "v2")
        val served = given().accept("application/json").get("/q/openapi")
            .then().statusCode(200).extract().jsonPath()
        assertThat(served.getMap<String, Any>("paths").keys).contains(
            "/api/v2/funds",
            "/api/v2/navs/{navId}",
            "/api/v2/strategies",
            "/api/v2/strategy-changes/{changeId}",
            "/api/v2/contracts/{contractId}/holdings",
            "/api/v2/reporting/funds/{fundId}/period-figures",
            "/api/v2/position-classification-corrections",
        )
        assertThat(served.getList<String>("components.schemas.CreateFundRequest.required"))
            .contains("productRevisionId")
        assertThat(served.getList<String>("components.schemas.AmendFundRequest.required"))
            .doesNotContain("productRevisionId")
    }

    @Test
    fun `legacy reads advertise sunset but legacy fund definition writes are not registered`() {
        given().get("/api/v1/funds").then().statusCode(200)
            .header("X-API-Version", "v1")
            .header("Deprecation", "true")
            .header("Sunset", "Wed, 30 Jun 2027 00:00:00 GMT")
            .header("Link", "</api/v2/funds>; rel=\"successor-version\"")
        val legacyBody = definitionBody().filterKeys { it != "productRevisionId" } +
            ("managementFeeRate" to BigDecimal("0.008"))
        given().contentType("application/json").body(legacyBody)
            .post("/api/v1/funds").then().statusCode(405)
        given().contentType("application/json").body(legacyBody)
            .put("/api/v1/funds/${UUID.randomUUID()}").then().statusCode(405)
    }

    private fun definitionBody(): Map<String, Any> = mapOf(
        "name" to "Versioned revision fixture",
        "isin" to ("PF" + UUID.randomUUID().toString().replace("-", "").take(9).uppercase() + "0"),
        "lei" to "315700ABCDEF12345678",
        "depositaryReference" to "TEST-DEP",
        "custodyAccountReference" to "TEST-CUST",
        "currency" to "CZK",
        "riskClass" to 3,
        "productRevisionId" to PrecisionCatalogTerms.REVISION.toString(),
    )

    private fun connection(): Connection {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }
}

/** Only the catalog boundary is replaced; HTTP, persistence, and NAV calculation remain real. */
@Alternative
@ApplicationScoped
class PrecisionCatalogTerms : FundTermsPort {
    override suspend fun resolve(revisionId: UUID): PinnedFundTerms = FundRevisionTerms.parse(
        revisionId,
        ObjectMapper().readTree(
            """{"id":"$REVISION","state":"PUBLISHED",
                "schemaRef":{"id":"org.openbank.retirement.pension-savings","version":2},
                "content":{"attributes":{"feeSchedule":{"managementFeeAnnualPercent":"0.123456"}}}}
            """.trimIndent(),
        ),
    )

    companion object {
        val REVISION: UUID = UUID.fromString("00000000-0000-0000-0000-000000000056")
    }
}
