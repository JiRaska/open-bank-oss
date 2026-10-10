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
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import java.io.File

/**
 * #10486 batch 3: `POST /api/v1/parties` admits ROLE_API so kyb-service can create the entity party
 * as its OWN principal once the shared `openbank-services` client loses ROLE_OPERATOR. ROLE_API is
 * held by EVERY service account and party-service runs OPA advisory, so the named-caller check is
 * what refuses the rest.
 */
class PartyCreateCallerGuardTest {

    /**
     * #12448: a `service-account-<client>` name becomes that client's OWN verified service-account
     * token (`azp` = client, `preferred_username` = the name, `sub` set) — the only shape the guard
     * admits. Any other name stays a plain, non-JWT principal.
     */
    private fun identity(name: String, vararg roles: String) = if (name.startsWith("service-account-")) {
        jwtIdentity(name.removePrefix("service-account-"), name, *roles)
    } else {
        QuarkusSecurityIdentity.builder().setPrincipal(QuarkusPrincipal(name)).addRoles(roles.toSet()).build()
    }

    private fun jwtIdentity(azp: String?, username: String?, vararg roles: String, sub: String? = "sa-subject") =
        QuarkusSecurityIdentity.builder()
            .setPrincipal(TestJwt(mapOf("azp" to azp, "preferred_username" to username, "sub" to sub)))
            .addRoles(roles.toSet())
            .build()

    private class TestJwt(private val claims: Map<String, Any?>) : JsonWebToken {
        override fun getName(): String = claims["preferred_username"] as String? ?: "anonymous"
        override fun getClaimNames(): Set<String> = claims.filterValues { it != null }.keys

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> getClaim(claimName: String): T = claims[claimName] as T
    }

    @Test
    fun `kyb's own principal with ROLE_API only may create a party`() {
        assertThatCode { requireNamedPartyCreateCaller(identity("service-account-openbank-kyb", "ROLE_API")) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `another service account holding ROLE_API is refused`() {
        val other = identity("service-account-openbank-mcp-service", "ROLE_API")
        assertThatThrownBy { requireNamedPartyCreateCaller(other) }
            .isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `the shared client is refused once it holds ROLE_API only`() {
        val shared = identity("service-account-openbank-services", "ROLE_API")
        assertThatThrownBy { requireNamedPartyCreateCaller(shared) }
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

    @Test
    fun `an allowed principal's name on a token issued to another client is refused`() {
        val impostor = jwtIdentity("openbank-admin-ui", "service-account-openbank-kyb", "ROLE_API")
        assertThatThrownBy { requireNamedPartyCreateCaller(impostor) }.isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `a human token issued through an allowed client, or a non-JWT principal so named, is refused`() {
        val human = jwtIdentity("openbank-kyb", "alice", "ROLE_API")
        assertThatThrownBy { requireNamedPartyCreateCaller(human) }.isInstanceOf(ForbiddenException::class.java)
        val named = QuarkusSecurityIdentity.builder()
            .setPrincipal(QuarkusPrincipal("service-account-openbank-kyb"))
            .addRole("ROLE_API")
            .build()
        assertThatThrownBy { requireNamedPartyCreateCaller(named) }.isInstanceOf(ForbiddenException::class.java)
        val noSubject = jwtIdentity("openbank-kyb", "service-account-openbank-kyb", "ROLE_API", sub = null)
        assertThatThrownBy { requireNamedPartyCreateCaller(noSubject) }.isInstanceOf(ForbiddenException::class.java)
    }
}
