// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.contract

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.sql.DataSource

/**
 * The publish-ir backtest gate matches the checker by JWT issuer + subject, as the role approval
 * does, never by display name: a backtest by a different subject that happens to carry the same
 * `preferred_username` does not unlock the publish.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_products")],
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PublishIrBacktestIdentityIT {

    @Inject
    lateinit var dataSource: DataSource

    @Test
    @Order(1)
    @TestSecurity(user = "test-operator", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"), Claim(key = "iss", value = ISSUER),
            Claim(key = "sub", value = "u-impostor"), Claim(key = "preferred_username", value = "test-operator"),
        ],
    )
    fun `a different subject with the same name runs the golden backtest`() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
        val spec =
            create(
                "/api/v2/specifications",
                """{"code":"SAV_ID_$suffix","schemaRef":{"id":"org.openbank.banking.deposit","version":2}}""",
            )
        offeringId =
            create("/api/v2/offerings", """{"specificationId":"$spec","code":"SAV_ID_${suffix}_CZ","market":$MARKET}""")
        revisionId = create(
            "/api/v2/offerings/$offeringId/revisions",
            """{"schemaRef":{"id":"org.openbank.banking.deposit","version":2},"name":{"en":"Savings"},""" +
                """"attributes":$DEPOSIT_ATTRIBUTES,"behaviour":$IR}""",
        )
        dataSource.connection.use { c ->
            c.prepareStatement(
                "UPDATE catalog_revisions SET maker_id = 'independent-ir-author' WHERE id = ?",
            ).use { st ->
                st.setObject(1, UUID.fromString(revisionId))
                assertThat(st.executeUpdate()).isEqualTo(1)
            }
        }
        Given {
            contentType("application/json")
            body("""{"baseRevisionId":"$revisionId"}""")
        } When { post("$base/behaviour-diff") } Then { statusCode(200) }
    }

    @Test
    @Order(2)
    @TestSecurity(user = "test-operator", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"), Claim(key = "iss", value = ISSUER),
            Claim(key = "sub", value = "u-op"), Claim(key = "preferred_username", value = "test-operator"),
        ],
    )
    fun `the checker's publish-ir is a 409 - the evidence belongs to another subject`() {
        Given {
            contentType("application/json")
            header("If-Match", "\"0\"")
            body("""{"reason":"impostor evidence"}""")
        } When { post("$base/publish-ir") } Then { statusCode(409) }
    }

    private val base get() = "/api/v2/offerings/$offeringId/revisions/$revisionId"

    private fun create(path: String, json: String): String = Given {
        contentType("application/json")
        body(json)
    } When { post(path) } Then { statusCode(201) } Extract { path<String>("id") }

    companion object {
        const val ISSUER = "https://example.invalid/catalog-issuer"
        const val MARKET = """{"countries":["CZ"],"channels":["WEB"],"locales":["cs-CZ"]}"""
        const val DEPOSIT_ATTRIBUTES =
            """{"currency":"CZK","productType":"SAVINGS","interest":{"rateType":"FIXED","dayCount":"ACT_365","payoutFrequency":"MONTHLY","annualRate":"0.0425"}}"""
        val IR: String = Files.readString(Path.of("..", "openbank-products", "ir", "savings-fixed.json")).trimEnd()
        private var offeringId: String? = null
        private var revisionId: String? = null
    }
}
