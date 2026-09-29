// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.rest

import io.quarkus.security.runtime.QuarkusPrincipal
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.ForbiddenException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * #10486 batch 6: `GET /api/v1/analytics/credit-profile/{partyId}` admits ROLE_API so copilot and
 * lending can read it as their OWN principals once the shared `openbank-services` client loses
 * ROLE_OPERATOR. ROLE_API is held by EVERY service account and analytics-sink has no OPA sidecar, so
 * the named-caller check is what refuses the rest.
 */
class CreditProfileCallerGuardTest {

    private fun identity(name: String, vararg roles: String) = QuarkusSecurityIdentity.builder()
        .setPrincipal(QuarkusPrincipal(name))
        .addRoles(roles.toSet())
        .build()

    @Test
    fun `copilot's and lending's own principals with ROLE_API only may read a credit profile`() {
        listOf("service-account-openbank-copilot", "service-account-openbank-lending").forEach { name ->
            assertThatCode { requireNamedCreditProfileCaller(identity(name, "ROLE_API")) }
                .describedAs(name)
                .doesNotThrowAnyException()
        }
    }

    @Test
    fun `another service account holding ROLE_API is refused`() {
        listOf("service-account-openbank-mcp", "service-account-openbank-party").forEach { name ->
            assertThatThrownBy { requireNamedCreditProfileCaller(identity(name, "ROLE_API")) }
                .describedAs(name)
                .isInstanceOf(ForbiddenException::class.java)
        }
    }

    @Test
    fun `the shared client is refused once it holds ROLE_API only`() {
        assertThatThrownBy {
            requireNamedCreditProfileCaller(identity("service-account-openbank-services", "ROLE_API"))
        }
            .isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `staff roles keep the pre-#10486 behaviour`() {
        listOf("ROLE_OPERATOR", "ROLE_AUDITOR", "ROLE_ADMIN").forEach { role ->
            assertThatCode { requireNamedCreditProfileCaller(identity("u-staff", role)) }
                .describedAs(role)
                .doesNotThrowAnyException()
        }
    }

    @Test
    fun `the credit profile endpoint admits ROLE_API at the RBAC gate`() {
        val m = CreditProfileResource::class.java.declaredMethods.first { it.name == "profile" }
        assertThat(m.getAnnotation(RolesAllowed::class.java).value).contains("ROLE_API")
    }

    /**
     * The caller set is only right while each caller actually presents that identity: its credit
     * profile rest-client selects a named oidc-client whose client-id is the principal's suffix.
     */
    @Test
    fun `each named caller's own config mints from the client this set names`() {
        val expected = mapOf(
            "service-account-openbank-copilot" to "../openbank-copilot-service",
            "service-account-openbank-lending" to "../openbank-lending-service",
        )
        assertThat(expected.keys).isEqualTo(CREDIT_PROFILE_CALLERS)
        expected.forEach { (principal, module) ->
            val doc = Yaml().load<Map<String, Any>>(
                File("$module/src/main/resources/application.yaml").readText(),
            )
            val oidcClient = (doc["quarkus"] as Map<*, *>)["oidc-client"] as Map<*, *>
            val m2m = oidcClient["m2m"] as Map<*, *>
            assertThat("service-account-${m2m["client-id"]}").describedAs(module).isEqualTo(principal)
        }
    }
}
