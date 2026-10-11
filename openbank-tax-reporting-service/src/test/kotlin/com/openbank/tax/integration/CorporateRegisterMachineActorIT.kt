// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test

/**
 * The corporate register feeds ČNB PSP 32-04, 50-04 and 40-01, and its maker/checker is a person's
 * attestation. A Keycloak client_credentials token is classified HUMAN and the shared
 * `service-account-openbank-services` holds ROLE_OPERATOR in some realms, so `@RolesAllowed` alone
 * let a machine propose a figure, or approve/reject one as the four-eyes checker.
 * The status codes discriminate: past authorisation, propose answers 201 and approve/reject of an
 * absent entry answer 404 — only 403 means the caller was refused.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_tax_reporting_corpreg_machine_it")],
)
@QuarkusTestResource(StatutoryReturnMessagingTestResource::class)
class CorporateRegisterMachineActorIT {
    private val path = "/api/v1/corporate-register"
    private val absentId = "00000000-0000-0000-0000-000000000098"
    private val proposeBody = """{"entityId":"company","fact":"SHARE_CAPITAL","value":1000,
        "effectiveFrom":"2009-01-01","reason":"annual update","evidence":"minutes"}"""

    private fun assertEveryChangeRouteRefused() {
        Given {
            contentType("application/json")
            body(proposeBody)
        } When { post(path) } Then {
            statusCode(403)
            body("error", containsString("service account"))
        }
        Given { contentType("application/json") } When { post("$path/$absentId/approve") } Then { statusCode(403) }
        Given { contentType("application/json") } When { post("$path/$absentId/reject") } Then { statusCode(403) }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-services"),
            Claim(key = "preferred_username", value = "service-account-openbank-services"),
        ],
    )
    fun `shared service account with ROLE_OPERATOR cannot propose, approve or reject`() {
        assertEveryChangeRouteRefused()
    }

    @Test
    @TestSecurity(user = "8d0c1f3e-2b4a-4c55-9e1a-3f2b6c7d8e92", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "upn", value = "8d0c1f3e-2b4a-4c55-9e1a-3f2b6c7d8e92"),
            Claim(key = "azp", value = "openbank-services"),
            Claim(key = "preferred_username", value = "service-account-openbank-services"),
        ],
    )
    fun `a service-account token is refused even when its principal name is not the username`() {
        assertEveryChangeRouteRefused()
    }

    @Test
    @TestSecurity(user = "8d0c1f3e-2b4a-4c55-9e1a-3f2b6c7d8e93", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "upn", value = "8d0c1f3e-2b4a-4c55-9e1a-3f2b6c7d8e93"),
            Claim(key = "client_id", value = "openbank-services"),
        ],
    )
    fun `a client_credentials token is refused by its client_id claim alone`() {
        assertEveryChangeRouteRefused()
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "preferred_username", value = "operator"),
        ],
    )
    fun `a human operator still reaches the change routes`() {
        Given {
            contentType("application/json")
            body(proposeBody)
        } When { post(path) } Then { statusCode(201) }
        Given { contentType("application/json") } When { post("$path/$absentId/approve") } Then { statusCode(404) }
        Given { contentType("application/json") } When { post("$path/$absentId/reject") } Then { statusCode(404) }
    }
}
