// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.productcatalog.infrastructure.persistence.BankV1CompatibilityBackfill
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * Drives the real scheduler rather than calling [BankV1CompatibilityBackfill.reconcileAfterRollingWriters]
 * directly. The regression is context-sensitive: a direct call can supply the Vert.x context a
 * scheduler dispatch must create itself.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_catalog_scheduler")],
)
@TestProfile(CatalogReconciliationSchedulerVertxContextIT.FastReconciliationProfile::class)
@TestSecurity(user = "scheduler-operator", roles = ["ROLE_OPERATOR"])
class CatalogReconciliationSchedulerVertxContextIT {
    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var mapper: ObjectMapper

    class FastReconciliationProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.catalog.bank-v1-reconcile-interval" to "1s",
            "openbank.catalog.bank-v1-reconcile-initial-delay" to "0s",
        )
    }

    @Test
    fun `scheduled reconciliation repairs a legacy write after startup`() {
        val created = given().contentType("application/json")
            .body("""{"code":"SCHEDULER_CONTEXT","name":"Before scheduler","type":"CURRENT","currency":"EUR"}""")
            .post("/api/v1/products").then().statusCode(201).extract()
        val productId = UUID.fromString(created.jsonPath().getString("id"))
        val changed = (mapper.readTree(created.asString()) as ObjectNode).apply { put("name", NEW_NAME) }

        dataSource.connection.use { connection ->
            connection.prepareStatement(UPDATE_LEGACY_DOCUMENT_SQL).use { statement ->
                statement.setString(1, mapper.writeValueAsString(changed))
                statement.setObject(2, productId)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }

        val deadline = System.nanoTime() + BUDGET_NANOS
        var content = latestDraftContent(productId)
        while (!content.contains(NEW_NAME) && System.nanoTime() < deadline) {
            Thread.sleep(POLL_INTERVAL_MILLIS)
            content = latestDraftContent(productId)
        }

        assertThat(content)
            .describedAs("the real scheduled reconciliation must run with a Vert.x context and update the mapped draft")
            .contains(NEW_NAME)
    }

    private fun latestDraftContent(productId: UUID): String = dataSource.connection.use { connection ->
        queryLatestDraftContent(connection, productId)
    }

    private fun queryLatestDraftContent(connection: Connection, productId: UUID): String =
        connection.prepareStatement(LATEST_DRAFT_CONTENT_SQL).use { statement ->
            statement.setObject(1, productId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else "" }
        }

    private companion object {
        const val NEW_NAME = "Changed by scheduled reconciliation"
        const val BUDGET_NANOS = 60_000_000_000L
        const val POLL_INTERVAL_MILLIS = 250L
        const val UPDATE_LEGACY_DOCUMENT_SQL = "UPDATE products SET doc = to_jsonb(CAST(? AS text)) WHERE id = ?"
        const val LATEST_DRAFT_CONTENT_SQL =
            "SELECT r.content::text FROM catalog_revisions r " +
                "JOIN bank_v1_product_mapping m ON m.default_offering_id = r.offering_id " +
                "WHERE m.product_id = ? AND r.state = 'DRAFT' ORDER BY r.revision_no DESC LIMIT 1"
    }
}
