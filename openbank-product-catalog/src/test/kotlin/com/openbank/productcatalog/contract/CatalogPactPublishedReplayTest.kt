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
        publishFixture(first)
        val second = fixtures.independentlyCheckableDraftExists()
        assertNotEquals(first["publishOfferingId"], second["publishOfferingId"])
        assertNotEquals(first["publishRevisionId"], second["publishRevisionId"])
        publishFixture(second)
        given().get(fixtureRevisionPath(first)).then().statusCode(200).body("state", equalTo("PUBLISHED"))
    }

    private fun publishFixture(state: Map<String, Any>) {
        given().contentType("application/json")
            .header("If-Match", "\"0\"")
            .body("""{"reason":"independent commercial approval"}""")
            .post("${fixtureRevisionPath(state)}/publish")
            .then().statusCode(200)
            .body("state", equalTo("PUBLISHED"))
    }

    private fun fixtureRevisionPath(state: Map<String, Any>): String =
        "/api/v2/offerings/${state["publishOfferingId"]}/revisions/${state["publishRevisionId"]}"
}
