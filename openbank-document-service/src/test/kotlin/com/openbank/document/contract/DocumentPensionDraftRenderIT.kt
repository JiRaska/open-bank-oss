// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.contract

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hamcrest.Matchers.equalTo
import org.hibernate.reactive.mutiny.Mutiny
import org.junit.jupiter.api.Test

/** Production's unreviewed seed must refuse a real authenticated render, even with a complete request. */
@QuarkusTest
@QuarkusTestResource(com.openbank.document.it.PostgresRedisTestResource::class)
@TestSecurity(user = "draft-render-verifier", roles = ["ROLE_API"])
class DocumentPensionDraftRenderIT {
    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var sessionFactory: Mutiny.SessionFactory

    @Test
    fun `unreviewed pension KID draft refuses real HTTP render`() {
        PensionPublishedPactState.restoreDraftInTestDatabase(sessionFactory)
        given()
            .baseUri("http://localhost:$testPort")
            .contentType("application/json")
            .body(
                """
                {"templateCode":"pension-dps-key-information-cs","contentType":"application/pdf",
                 "data":{"legalReviewRequired":true,"applicationId":"a0000000-0000-4000-8000-000000000001",
                         "documentType":"PRE_CONTRACTUAL_INFORMATION","productLine":"DPS","strategyCode":"BALANCED"},
                 "partyRef":"b0000000-0000-4000-8000-000000000001",
                 "caseRef":"a0000000-0000-4000-8000-000000000001","productRef":"pension-dps","retainUntil":null}
                """.trimIndent(),
            )
            .post("/api/v1/documents/render")
            .then()
            .statusCode(422)
            .body("code", equalTo("BUSINESS_RULE_VIOLATION"))
            .body("message", equalTo("No published template for pension-dps-key-information-cs"))
    }
}
