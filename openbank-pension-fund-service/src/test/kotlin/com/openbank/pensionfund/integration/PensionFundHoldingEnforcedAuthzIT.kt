// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.integration

import com.openbank.pensionfund.it.PensionFundOpaTestResource
import com.openbank.pensionfund.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Test
import java.util.UUID

/** Real HTTP + enforced, generated OPA policy: both security layers must agree. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(PensionFundOpaTestResource::class, restrictToAnnotatedClass = true)
@TestProfile(PensionFundHoldingEnforcedAuthzIT.EnforcedProfile::class)
class PensionFundHoldingEnforcedAuthzIT {
    class EnforcedProfile : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf("authz.enforce" to "true")
    }

    private val ordersPath = "/api/v1/contracts/${UUID.randomUUID()}/orders"

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_OPERATOR", "ROLE_COMPLIANCE"])
    fun `shared service account is denied even when HTTP role gate admits it`() {
        given().get(ordersPath).then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "compliance-reviewer", roles = ["ROLE_COMPLIANCE"])
    fun `human compliance reader passes HTTP and OPA gates`() {
        given().get(ordersPath).then().statusCode(200)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-pension", roles = ["ROLE_API"])
    fun `own pension client passes HTTP and OPA gates`() {
        given().get(ordersPath).then().statusCode(200)
    }
}
