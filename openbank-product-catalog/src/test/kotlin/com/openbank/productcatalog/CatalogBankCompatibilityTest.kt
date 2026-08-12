// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.productcatalog.infrastructure.persistence.BankV1CompatibilityBackfill
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_products")],
)
@TestSecurity(user = "test-operator", roles = ["ROLE_OPERATOR"])
class CatalogBankCompatibilityTest {
    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var backfill: BankV1CompatibilityBackfill

    @Test
    fun `legacy backfill preserves all canonical ids and is idempotent`() {
        val products = scalar("SELECT COUNT(*) FROM products")
        assertThat(products).isGreaterThan(0)
        assertThat(scalar("SELECT COUNT(*) FROM bank_v1_product_mapping")).isEqualTo(products)
        assertThat(
            scalar(
                """SELECT COUNT(*) FROM bank_v1_product_mapping m """ +
                    """JOIN catalog_specifications s ON s.id = m.product_id """ +
                    """JOIN products p ON p.id = m.product_id WHERE s.code = p.code""",
            ),
        ).isEqualTo(products)

        assertThat(backfill.run()).isZero()
        assertThat(scalar("SELECT COUNT(*) FROM bank_v1_product_mapping")).isEqualTo(products)
    }

    @Test
    fun `v1 draft changes and v2 publication share one lossless banking projection`() {
        val code = "CURRENT_COMPAT_${UUID.randomUUID().toString().take(8).uppercase()}"
        val created = given()
            .contentType("application/json")
            .body("""{"code":"$code","name":"Compatibility draft","type":"CURRENT","currency":"EUR"}""")
            .post("/api/v1/products")
            .then()
            .statusCode(201)
            .header("ETag", equalTo("\"0\""))
            .extract()
        val productId = UUID.fromString(created.jsonPath().getString("id"))
        val offeringId = mappedOffering(productId)
        val revisionId = latestDraft(offeringId)

        given()
            .contentType("application/json")
            .header("If-Match", "\"0\"")
            .body("""{"code":"$code","name":"Compatibility edited","type":"CURRENT","currency":"EUR"}""")
            .put("/api/v1/products/$productId")
            .then()
            .statusCode(200)
            .header("ETag", equalTo("\"1\""))

        assertThat(legacyName(revisionId)).isEqualTo("Compatibility edited")
        assertThat(changeCount(revisionId, "BANK_V1_DRAFT_UPDATED")).isEqualTo(1)
        setMaker(revisionId, "independent-bank-maker")

        given()
            .contentType("application/json")
            .header("If-Match", "\"1\"")
            .body("""{"reason":"approved compatibility projection"}""")
            .post("/api/v2/offerings/$offeringId/revisions/$revisionId/publish")
            .then()
            .statusCode(200)

        given()
            .get("/api/v1/products/$productId")
            .then()
            .statusCode(200)
            .body("id", equalTo(productId.toString()))
            .body("code", equalTo(code))
            .body("name", equalTo("Compatibility edited"))
            .body("status", equalTo("ACTIVE"))
    }

    private fun mappedOffering(productId: UUID): UUID = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT default_offering_id FROM bank_v1_product_mapping WHERE product_id = ?",
        ).use { statement ->
            statement.setObject(1, productId)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getObject(1, UUID::class.java)
            }
        }
    }

    private fun latestDraft(offeringId: UUID): UUID = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT id FROM catalog_revisions WHERE offering_id = ? AND state = 'DRAFT' ORDER BY revision_no DESC",
        ).use { statement ->
            statement.setObject(1, offeringId)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getObject(1, UUID::class.java)
            }
        }
    }

    private fun legacyName(revisionId: UUID): String = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT (content->'attributes'->>'legacyDocument')::jsonb->>'name' FROM catalog_revisions WHERE id = ?",
        ).use { statement ->
            statement.setObject(1, revisionId)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getString(1)
            }
        }
    }

    private fun setMaker(revisionId: UUID, maker: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE catalog_revisions SET maker_id = ? WHERE id = ?").use { statement ->
                statement.setString(1, maker)
                statement.setObject(2, revisionId)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }
    }

    private fun changeCount(revisionId: UUID, action: String): Long = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT COUNT(*) FROM catalog_audit WHERE aggregate_id = ? AND action = ?",
        ).use { statement ->
            statement.setObject(1, revisionId)
            statement.setString(2, action)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }
    }

    private fun scalar(sql: String): Long = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }
    }
}
