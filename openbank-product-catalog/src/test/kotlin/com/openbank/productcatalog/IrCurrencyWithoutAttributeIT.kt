// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.openbank.libs.testing.containers.PostgresTestResource
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
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.sql.DataSource

/**
 * ADR-0352 §7: a schema without a `currency` attribute leaves the write nothing to compare the IR's
 * currency with, so the backtest compares it with the golden portfolio's accounts instead. An IR in
 * another currency than the offering's golden portfolio is a 409 and records nothing, so it cannot
 * borrow a portfolio in which its fees and thresholds mean something else.
 */
@QuarkusTest
@TestProfile(ConformancePackApprovalPolicyIT.ConformancePack::class)
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_products")],
)
class IrCurrencyWithoutAttributeIT {

    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = "test-operator", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "iss", value = "https://example.invalid/catalog-issuer"),
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "u-op"),
            Claim(key = "preferred_username", value = "test-operator"),
        ],
    )
    fun `an IR in another currency than its golden portfolio is refused at the backtest`() {
        val offeringId = GoldenOfferings.offering(dataSource, "SAVINGS_FIXED", SCHEMA_ID, 1, """{"countries":["CZ"]}""")
        val ir = IR.replace("\"currency\":\"EUR\"", "\"currency\":\"CZK\"")
        assertThat(ir).isNotEqualTo(IR)
        val revisionId = Given {
            contentType("application/json")
            body(
                """{"schemaRef":{"id":"$SCHEMA_ID","version":1},"name":{"en":"Widget"},""" +
                    """"attributes":{"readiness":"READY"},"behaviour":$ir}""",
            )
        } When { post("/api/v2/offerings/$offeringId/revisions") } Then { statusCode(201) } Extract {
            UUID.fromString(jsonPath().getString("id"))
        }
        Given {
            contentType("application/json")
            body("""{"baseRevisionId":"$revisionId"}""")
        } When { post("/api/v2/offerings/$offeringId/revisions/$revisionId/behaviour-diff") } Then { statusCode(409) }
        val rows = dataSource.connection.use { c ->
            c.prepareStatement("SELECT count(*) FROM catalog_revision_backtests WHERE revision_id = ?").use { st ->
                st.setObject(1, revisionId)
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
        assertThat(rows).isZero()
    }

    private companion object {
        const val SCHEMA_ID = "org.openbank.conformance.widget"
        val IR: String = Files.readString(Path.of("..", "openbank-products", "ir", "savings-fixed.json")).trimEnd()
    }
}
