// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.rest

import com.openbank.libs.authz.Authorize
import io.quarkus.security.runtime.QuarkusPrincipal
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.ForbiddenException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.File

/**
 * #10486 batch 3: `POST /api/v1/parties` admits ROLE_API so kyb-service can create the entity party
 * as its OWN principal once the shared `openbank-services` client loses ROLE_OPERATOR. ROLE_API is
 * held by EVERY service account and party-service runs OPA advisory, so the named-caller check is
 * what refuses the rest.
 */
class PartyCreateCallerGuardTest {

    private fun identity(name: String, vararg roles: String) = QuarkusSecurityIdentity.builder()
        .setPrincipal(QuarkusPrincipal(name))
        .addRoles(roles.toSet())
        .build()

    @Test
    fun `kyb's own principal with ROLE_API only may create a party`() {
        assertThatCode { requireNamedPartyCreateCaller(identity("service-account-openbank-kyb", "ROLE_API")) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `another service account holding ROLE_API is refused`() {
        assertThatThrownBy { requireNamedPartyCreateCaller(identity("service-account-openbank-mcp-service", "ROLE_API")) }
            .isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `the shared client is refused once it holds ROLE_API only`() {
        assertThatThrownBy { requireNamedPartyCreateCaller(identity("service-account-openbank-services", "ROLE_API")) }
            .isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `staff roles keep the pre-#10486 behaviour`() {
        listOf("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_KYC").forEach { role ->
            assertThatCode { requireNamedPartyCreateCaller(identity("u-staff", role)) }
                .describedAs(role)
                .doesNotThrowAnyException()
        }
    }

    @Test
    fun `createParty admits ROLE_API and carries the party create action`() {
        val m = PartyResource::class.java.declaredMethods.single { it.name == "createParty" }
        assertThat(m.getAnnotation(RolesAllowed::class.java).value).contains("ROLE_API")
        assertThat(m.getAnnotation(Authorize::class.java).action).isEqualTo("party.create")
    }

    @Test
    fun `the Kotlin caller set and the rego identity rule list the same principals`() {
        val rego = File("../openbank-infra/gitops/components/party/party_rest_ext.rego").readText()
        val rule = rego.substringAfter("allowed_reasons contains \"service-kyb-party-m2m\"")
            .substringBefore("\n}")
        val inRego = Regex("\"(service-account-[a-z0-9-]+)\"").findAll(rule).map { it.groupValues[1] }.toSet()
        assertThat(rule).contains("\"party.create\"")
        assertThat(inRego).isEqualTo(PARTY_CREATE_CALLERS)
    }
}
