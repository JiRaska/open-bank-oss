// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.dispute.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test

/**
 * A pure ROLE_AUDITOR may read disputes (admin-ui `compliance:view`, rego `dispute-auditor-read`)
 * and may not write them. Real HTTP, so `@RolesAllowed` is what decides; authz runs advisory
 * because no OPA sidecar exists under test.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(DisputeAuditorReadRolesIT.AuthzOffProfile::class)
@TestSecurity(user = "auditor", roles = ["ROLE_AUDITOR"])
class DisputeAuditorReadRolesIT {

    class AuthzOffProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("authz.enforce" to "false")
    }

    @Test
    fun `an auditor can list disputes`() {
        Given { this } When { get("/api/v1/disputes") } Then { statusCode(200) }
    }

    @Test
    fun `an auditor can list disputes for an account`() {
        Given { this } When { get("/api/v1/disputes/account/$ACCOUNT_ID") } Then { statusCode(200) }
    }

    @Test
    fun `an auditor cannot open a dispute`() {
        Given {
            contentType("application/json")
            body("{}")
        } When {
            post("/api/v1/disputes")
        } Then {
            statusCode(403)
        }
    }

    private companion object {
        const val ACCOUNT_ID = "00000000-0000-0000-0000-0000000000a1"
    }
}
