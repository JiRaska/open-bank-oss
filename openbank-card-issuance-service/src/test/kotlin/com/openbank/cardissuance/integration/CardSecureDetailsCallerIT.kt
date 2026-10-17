// SPDX-License-Identifier: Apache-2.0
package com.openbank.cardissuance.integration

import com.openbank.cardissuance.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test
import java.util.UUID

/** Actual HTTP routing and caller-guard proof; OIDC test identities do not verify JWT signatures. */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(CardSecureDetailsCallerProfile::class)
class CardSecureDetailsCallerIT {
    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "sub", value = "synthetic-subject"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `verified edge passes the caller guard`() {
        assertMissingCardStatus(404)
    }

    @Test
    @TestSecurity(user = "synthetic-staff", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "synthetic-subject"),
            Claim(key = "preferred_username", value = "synthetic-staff"),
        ],
    )
    fun `staff cannot read secure details`() {
        assertMissingCardStatus(403)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "synthetic-subject"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `admin client cannot impersonate edge username`() {
        assertMissingCardStatus(403)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-delegation", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-delegation"),
            Claim(key = "sub", value = "synthetic-subject"),
            Claim(key = "preferred_username", value = "service-account-openbank-delegation"),
        ],
    )
    fun `another service account cannot read secure details`() {
        assertMissingCardStatus(403)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_VIEWER"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "sub", value = "synthetic-subject"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `viewer role cannot read secure details`() {
        assertMissingCardStatus(403)
    }

    private fun assertMissingCardStatus(expected: Int) {
        // A missing synthetic ID reaches the lookup only after the caller guard; no PAN is read.
        Given { this } When {
            get("/api/v1/cards/${UUID.randomUUID()}/secure-details")
        } Then {
            statusCode(expected)
        }
    }
}

class CardSecureDetailsCallerProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        // Isolate the Kotlin runtime guard from the independent OPA policy proof.
        "authz.enforce" to "false",
        "quarkus.http.test-port" to "0",
        "quarkus.http.test-ssl-port" to "0",
        "openbank.outbox.dispatch-enabled" to "false",
    )
}
