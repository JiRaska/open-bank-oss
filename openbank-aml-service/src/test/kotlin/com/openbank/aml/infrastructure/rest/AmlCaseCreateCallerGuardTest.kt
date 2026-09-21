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
import org.junit.jupiter.api.Test
import java.io.File

/**
 * #10486 batch 3: `POST /api/v1/aml/cases` admits ROLE_API so the payment/FX services can open a
 * case as their OWN principals once the shared `openbank-services` client loses ROLE_OPERATOR.
 * ROLE_API is held by EVERY service account, and aml-service runs OPA advisory, so the named-caller
 * check in the resource is what actually refuses the rest — these cases pin it.
 */
class AmlCaseCreateCallerGuardTest {

    private fun identity(name: String, vararg roles: String) = QuarkusSecurityIdentity.builder()
        .setPrincipal(QuarkusPrincipal(name))
        .addRoles(roles.toSet())
        .build()

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
        assertThatThrownBy { requireNamedMachineCaller(identity("service-account-openbank-mcp-service", "ROLE_API")) }
            .isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `the shared client is refused once it holds ROLE_API only`() {
        assertThatThrownBy { requireNamedMachineCaller(identity("service-account-openbank-services", "ROLE_API")) }
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
}
