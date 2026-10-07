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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.util.UUID

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

    @Test
    fun `published Product Studio pact state can be replayed without deleting the first revision`() {
        val first = fixtures.independentlyCheckableDraftExists()
        given().get("/api/v2/offerings/$LEGACY_OFFERING_ID/revisions/$LEGACY_REVISION_ID")
            .then().statusCode(200).body("state", equalTo("DRAFT"))
        publishFixture(first)
        val second = fixtures.independentlyCheckableDraftExists()
        assertNotEquals(first["publishOfferingId"], second["publishOfferingId"])
        assertNotEquals(first["publishRevisionId"], second["publishRevisionId"])
        publishFixture(second)
        given().get(fixtureRevisionPath(first)).then().statusCode(200).body("state", equalTo("PUBLISHED"))
        publishPath("/api/v2/offerings/$LEGACY_OFFERING_ID/revisions/$LEGACY_REVISION_ID")
    }

    @Test
    fun `unknown offering identity returns 404 during replay`() {
        given().get("/api/v2/offerings/${UUID.randomUUID()}/revisions/${UUID.randomUUID()}")
            .then().statusCode(404)
    }

    private fun publishFixture(state: Map<String, Any>) {
        publishPath(fixtureRevisionPath(state))
    }

    private fun publishPath(path: String) {
        given().contentType("application/json")
            .header("If-Match", "\"0\"")
            .body("""{"reason":"independent commercial approval"}""")
            .post("$path/publish")
            .then().statusCode(200)
            .body("state", equalTo("PUBLISHED"))
    }

    private fun fixtureRevisionPath(state: Map<String, Any>): String =
        "/api/v2/offerings/${state["publishOfferingId"]}/revisions/${state["publishRevisionId"]}"

    private companion object {
        const val LEGACY_OFFERING_ID = "20000000-0000-0000-0000-000000000003"
        const val LEGACY_REVISION_ID = "30000000-0000-0000-0000-000000000002"
    }
}
