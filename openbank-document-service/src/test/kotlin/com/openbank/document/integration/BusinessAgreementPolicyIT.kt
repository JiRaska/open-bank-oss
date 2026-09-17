// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.integration

import com.openbank.document.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Both business-agreement endpoints consult the policy decision point: with a denying PDP and
 * enforcement pinned on, a caller that passes the role gate is refused with 403 before anything
 * is rendered. An endpoint without `@Authorize` would answer 400/404 here instead.
 */
@QuarkusTest
@QuarkusTestResource(DocumentPartyBrowsePolicyIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(DocumentPartyBrowsePolicyIT.DenyingPolicyProfile::class)
class BusinessAgreementPolicyIT {

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_API"])
    fun `a denied decision blocks ensure with 403`() {
        given().contentType(ContentType.JSON)
            .body("""{"caseId":"${UUID.randomUUID()}","entityPartyId":"${UUID.randomUUID()}","lang":"cs"}""")
            .post("/api/v1/business-agreements")
            .then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_API"])
    fun `a denied decision blocks read with 403 rather than 404`() {
        given().get("/api/v1/business-agreements/${UUID.randomUUID()}").then().statusCode(403)
    }
}
