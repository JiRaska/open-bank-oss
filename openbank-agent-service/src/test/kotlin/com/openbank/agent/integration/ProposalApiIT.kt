// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.agent.integration

import com.openbank.agent.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.anyOf
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test

/**
 * First integration test for agent-service (ADR-0011 L2): boots the full Quarkus app against an
 * isolated PostgreSQL (Flyway migrates, Agroal datasource, CDI wiring all exercised) and drives the
 * proposal review surface end-to-end through HTTP. The proposal store is empty on a fresh container,
 * so listing pending proposals returns an empty array — a deterministic boot+query smoke that also
 * locks the RBAC boundary on the maker-checker endpoint (ADR-0031).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class ProposalApiIT {

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `GET proposals returns an empty list on a fresh store`() {
        given()
            .`when`().get("/api/v1/proposals?state=pending")
            .then()
            .statusCode(200)
            .body("size()", `is`(0))
    }

    @Test
    fun `GET proposals without an authenticated role is rejected`() {
        given()
            .`when`().get("/api/v1/proposals?state=pending")
            .then()
            .statusCode(anyOf(equalTo(401), equalTo(403)))
    }
}
