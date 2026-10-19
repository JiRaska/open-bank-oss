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
                ).post("/api/v1/funds").then().statusCode(201).extract().path<String>("id"),
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
                .post("/api/v1/funds/$fund/navs").then().statusCode(201).extract().path<String>("id"),
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
