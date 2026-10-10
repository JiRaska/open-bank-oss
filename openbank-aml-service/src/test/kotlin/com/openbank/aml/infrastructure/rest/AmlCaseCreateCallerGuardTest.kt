// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.rest

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
 * #10486 batch 3: `POST /api/v1/aml/cases` admits ROLE_API so the payment/FX services can open a
 * case as their OWN principals once the shared `openbank-services` client loses ROLE_OPERATOR.
 * ROLE_API is held by EVERY service account, and aml-service runs OPA advisory, so the named-caller
 * check in the resource is what actually refuses the rest — these cases pin it.
 */
class AmlCaseCreateCallerGuardTest {

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
        override fun getName(): String = claims["preferred_username"] as? String ?: "anonymous"
        override fun getClaimNames(): Set<String> = claims.filterValues { it != null }.keys

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> getClaim(claimName: String): T = claims[claimName] as T
    }

    @Test
    fun `each named payment or FX principal with ROLE_API only may open a case`() {
        AML_CASE_CREATE_CALLERS.forEach { caller ->
            assertThatCode { requireNamedMachineCaller(identity(caller, "ROLE_API")) }
                .describedAs(caller)
                .doesNotThrowAnyException()
        }
    }

    @Test
    fun `another service account holding ROLE_API is refused`() {
        val other = identity("service-account-openbank-mcp-service", "ROLE_API")
        assertThatThrownBy { requireNamedMachineCaller(other) }
            .isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `the shared client is refused once it holds ROLE_API only`() {
        val shared = identity("service-account-openbank-services", "ROLE_API")
        assertThatThrownBy { requireNamedMachineCaller(shared) }
            .isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `staff roles keep the pre-#10486 behaviour`() {
        listOf("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE").forEach { role ->
            assertThatCode { requireNamedMachineCaller(identity("u-staff", role)) }
                .describedAs(role)
                .doesNotThrowAnyException()
        }
    }

    @Test
    fun `createCase admits ROLE_API and carries the amlCase create action`() {
        val m = AmlCaseResource::class.java.declaredMethods.single { it.name == "createCase" }
        assertThat(m.getAnnotation(RolesAllowed::class.java).value).contains("ROLE_API")
        assertThat(m.getAnnotation(Authorize::class.java).action).isEqualTo("amlCase.create")
    }

    @Test
    fun `the Kotlin caller set and the rego identity rule list the same principals`() {
        val rego = File("../openbank-infra/gitops/components/aml/aml_rest_ext.rego").readText()
        val rule = rego.substringAfter("allowed_reasons contains \"service-aml-case-create-m2m\"")
            .substringBefore("\n}")
        val inRego = Regex("\"(service-account-[a-z0-9-]+)\"").findAll(rule).map { it.groupValues[1] }.toSet()
        assertThat(inRego).isEqualTo(AML_CASE_CREATE_CALLERS)
    }

    @Test
    fun `an allowed principal's name on a token issued to another client is refused`() {
        val impostor = jwtIdentity("openbank-admin-ui", "service-account-openbank-fx", "ROLE_API")
        assertThatThrownBy { requireNamedMachineCaller(impostor) }.isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `a human token issued through an allowed client, or a non-JWT principal so named, is refused`() {
        val human = jwtIdentity("openbank-fx", "alice", "ROLE_API")
        assertThatThrownBy { requireNamedMachineCaller(human) }.isInstanceOf(ForbiddenException::class.java)
        val named = QuarkusSecurityIdentity.builder()
            .setPrincipal(QuarkusPrincipal("service-account-openbank-fx"))
            .addRole("ROLE_API")
            .build()
        assertThatThrownBy { requireNamedMachineCaller(named) }.isInstanceOf(ForbiddenException::class.java)
        val noSubject = jwtIdentity("openbank-fx", "service-account-openbank-fx", "ROLE_API", sub = null)
        assertThatThrownBy { requireNamedMachineCaller(noSubject) }.isInstanceOf(ForbiddenException::class.java)
    }
}
