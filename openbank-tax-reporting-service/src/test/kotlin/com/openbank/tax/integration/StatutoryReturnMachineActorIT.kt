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
 * A Keycloak client_credentials token is classified HUMAN and the shared
 * `service-account-openbank-services` holds ROLE_OPERATOR in some realms, so `@RolesAllowed`
 * alone let a machine assemble, approve (as the four-eyes checker) or submit a ČNB return.
 * The status codes discriminate: past authorisation, assemble answers 503 (no data source bound)
 * and approve/submit answer 404 (no such return) — only 403 means the caller was refused.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_tax_reporting_machine_it")],
)
@QuarkusTestResource(StatutoryReturnMessagingTestResource::class)
class StatutoryReturnMachineActorIT {
    private val path = "/api/v1/statutory-returns"
    private val absentId = "00000000-0000-0000-0000-000000000099"
    private val assembleBody = """{
        "catalogueId":"cz-pension-cnb",
        "returnCode":"PSP10-12-PS",
        "entityId":"company",
        "period":"2025-01"
    }"""

    private fun assertEveryLifecycleRouteRefused() {
        Given {
            contentType("application/json")
            body(assembleBody)
        } When { post("$path/assemble") } Then {
            statusCode(403)
            body("error", containsString("service account"))
        }
        Given { contentType("application/json") } When { post("$path/$absentId/approve") } Then {
            statusCode(403)
        }
        Given {
            contentType("application/json")
            body("""{"reference":"REF"}""")
        } When { post("$path/$absentId/submitted") } Then {
            statusCode(403)
        }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-services"),
            Claim(key = "preferred_username", value = "service-account-openbank-services"),
        ],
    )
    fun `shared service account with ROLE_OPERATOR cannot assemble, approve or submit`() {
        assertEveryLifecycleRouteRefused()
    }

    @Test
    @TestSecurity(user = "service-account-openbank-pension", roles = ["ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-pension"),
            Claim(key = "preferred_username", value = "service-account-openbank-pension"),
        ],
    )
    fun `any other service account is refused whatever roles it holds`() {
        assertEveryLifecycleRouteRefused()
    }

    @Test
    @TestSecurity(user = "8d0c1f3e-2b4a-4c55-9e1a-3f2b6c7d8e90", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            // `upn` wins over `preferred_username` when Quarkus names the principal, so the
            // name check alone cannot see this token — only the claim checks can.
            Claim(key = "upn", value = "8d0c1f3e-2b4a-4c55-9e1a-3f2b6c7d8e90"),
            Claim(key = "azp", value = "openbank-services"),
            Claim(key = "preferred_username", value = "service-account-openbank-services"),
        ],
    )
    fun `a service-account token is refused even when its principal name is not the username`() {
        assertEveryLifecycleRouteRefused()
    }

    @Test
    @TestSecurity(user = "8d0c1f3e-2b4a-4c55-9e1a-3f2b6c7d8e91", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "upn", value = "8d0c1f3e-2b4a-4c55-9e1a-3f2b6c7d8e91"),
            Claim(key = "client_id", value = "openbank-services"),
        ],
    )
    fun `a client_credentials token is refused by its client_id claim alone`() {
        assertEveryLifecycleRouteRefused()
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "preferred_username", value = "operator"),
        ],
    )
    fun `a human operator still reaches the lifecycle routes`() {
        Given {
            contentType("application/json")
            body(assembleBody)
        } When { post("$path/assemble") } Then {
            statusCode(503)
        }
        Given { contentType("application/json") } When { post("$path/$absentId/approve") } Then {
            statusCode(404)
        }
    }
}
