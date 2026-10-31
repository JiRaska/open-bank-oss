// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.contract

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/** A provider-state replay must not try to delete the first immutable published revision. */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_products_pact_replay")],
    restrictToAnnotatedClass = true,
)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_OPERATOR"])
class CatalogPactPublishedReplayTest {
    @Inject
    lateinit var fixtures: CatalogPactProviderFixtures

    @Inject
    lateinit var dataSource: DataSource

    @Test
    fun `published Product Studio pact state can be replayed without deleting the first revision`() {
        val first = fixtures.independentlyCheckableDraftExists()
        publishFixture(first)
        // This broker interaction predates the provider-state path generator. Its fixed path
        // must survive a current Pact publish without changing the published first revision.
        fixtures.independentlyCheckableDraftExists()
        val historical = mapOf(
            "publishOfferingId" to "20000000-0000-0000-0000-000000000003",
            "publishRevisionId" to "30000000-0000-0000-0000-000000000002",
        )
        publishFixture(historical)
        val second = fixtures.independentlyCheckableDraftExists()
        assertNotEquals(first["publishOfferingId"], second["publishOfferingId"])
        assertNotEquals(first["publishRevisionId"], second["publishRevisionId"])
        publishFixture(second)
        given().get(fixtureRevisionPath(first)).then().statusCode(200).body("state", equalTo("PUBLISHED"))
        given().get(fixtureRevisionPath(historical)).then().statusCode(200).body("state", equalTo("PUBLISHED"))
        listOf(first, historical, second).forEach(::assertPublishedInPostgres)
    }

    @Test
    fun `unknown offering identity returns 404 during replay`() {
        given().get("/api/v2/offerings/${UUID.randomUUID()}/revisions/${UUID.randomUUID()}")
            .then().statusCode(404)
    }

    private fun publishFixture(state: Map<String, Any>) {
        given().contentType("application/json")
            .header("If-Match", "\"0\"")
            .body("""{"reason":"independent commercial approval"}""")
            .post("${fixtureRevisionPath(state)}/publish")
            .then().statusCode(200)
            .body("state", equalTo("PUBLISHED"))
    }

    private fun assertPublishedInPostgres(state: Map<String, Any>) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT state FROM catalog_revisions WHERE id = ?").use { statement ->
                statement.setObject(1, UUID.fromString(state["publishRevisionId"] as String))
                statement.executeQuery().use { rows ->
                    assertEquals(true, rows.next())
                    assertEquals("PUBLISHED", rows.getString(1))
                }
            }
        }
    }

    private fun fixtureRevisionPath(state: Map<String, Any>): String =
        "/api/v2/offerings/${state["publishOfferingId"]}/revisions/${state["publishRevisionId"]}"
}
