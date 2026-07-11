// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.securityscanner.infrastructure.rest

import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

/**
 * Regression guard: SecurityScannerResource and IctIncidentResource had zero security
 * annotations (and the service had no quarkus.oidc config at all) — every endpoint was
 * unauthenticated-accessible, exposing fleet-wide OWASP scan results (an attack roadmap) and
 * letting anyone falsify DORA regulator-facing incident status. Reflection, no boot needed.
 */
class SecurityScannerAuthzTest {

    private val httpVerbs =
        listOf(GET::class.java, POST::class.java, PUT::class.java, DELETE::class.java, PATCH::class.java)

    private fun Method.isHttpEndpoint() = httpVerbs.any { getAnnotation(it) != null }

    private fun assertEveryEndpointRoleGatedOrExplicitlyPublic(resourceClass: Class<*>) {
        val classLevelRoles = resourceClass.getAnnotation(RolesAllowed::class.java)
        val all = resourceClass.declaredMethods.filter { it.isHttpEndpoint() }
        assertThat(all)
            .describedAs("expected to find HTTP endpoints by reflection on %s", resourceClass.simpleName)
            .isNotEmpty

        all.forEach { m ->
            val methodRoles = m.getAnnotation(RolesAllowed::class.java)
            val permitAll = m.getAnnotation(PermitAll::class.java)
            assertThat(methodRoles != null || classLevelRoles != null || permitAll != null)
                .describedAs(
                    "%s.%s must be @RolesAllowed (method or class level) or explicitly @PermitAll",
                    resourceClass.simpleName,
                    m.name,
                )
                .isTrue()
        }
    }

    @Test
    fun `every SecurityScannerResource endpoint is role-gated or explicitly public`() {
        assertEveryEndpointRoleGatedOrExplicitlyPublic(SecurityScannerResource::class.java)
    }

    @Test
    fun `every IctIncidentResource endpoint is role-gated`() {
        assertEveryEndpointRoleGatedOrExplicitlyPublic(IctIncidentResource::class.java)
    }
}
