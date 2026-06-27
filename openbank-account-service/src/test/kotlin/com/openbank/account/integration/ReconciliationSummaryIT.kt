// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test

/**
 * Verifies account-service's [ReconciliationSummaryContract] implementation (ADR-0026, Phase 1): the
 * endpoint is registered from the inherited interface @Path, the role gate is enforced (never open), and
 * the JSON projection has the shape the analytics-sink parses.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.account.it.PostgresRedpandaRedisTestResource::class)
class ReconciliationSummaryIT {

    private val path = "/api/v1/analytics/reconciliation-summary"

    @Test
    fun `is role-gated - anonymous is rejected`() {
        Given { this } When { get(path) } Then { statusCode(401) }
    }

    @Test
    @TestSecurity(user = "svc", roles = ["ROLE_VIEWER"])
    fun `is role-gated - a disallowed role is forbidden`() {
        Given { this } When { get(path) } Then { statusCode(403) }
    }

    @Test
    @TestSecurity(user = "analytics-sink", roles = ["ROLE_SERVICE"])
    fun `the service-to-service caller gets the summary projection`() {
        Given { this } When {
            get(path)
        } Then {
            statusCode(200)
            body("service", equalTo("openbank-account-service"))
            body("generatedAt", notNullValue())
            body("countsByType", notNullValue())
            body("aggregates", notNullValue())
        }
    }
}
