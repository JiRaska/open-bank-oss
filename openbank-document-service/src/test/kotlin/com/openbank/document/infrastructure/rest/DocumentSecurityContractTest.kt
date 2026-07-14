// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.rest

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
 * Regression guard: every DocumentResource and SignatureCeremonyResource endpoint must be
 * role-gated — never @PermitAll and never unannotated (Quarkus's
 * `quarkus.security.jaxrs.deny-unannotated-endpoints` defaults to false). Reflection, no boot needed.
 */
class DocumentSecurityContractTest {

    private val httpVerbs =
        listOf(GET::class.java, POST::class.java, PUT::class.java, DELETE::class.java, PATCH::class.java)

    private fun Method.isHttpEndpoint() = httpVerbs.any { getAnnotation(it) != null }

    @Test
    fun `every document endpoint is role-gated, never permit-all or unannotated`() {
        val resources = listOf(DocumentResource::class.java, SignatureCeremonyResource::class.java)

        resources.forEach { resource ->
            val endpoints = resource.declaredMethods.filter { it.isHttpEndpoint() }
            assertThat(endpoints)
                .describedAs("expected HTTP endpoints on %s", resource.simpleName)
                .isNotEmpty

            endpoints.forEach { m ->
                assertThat(m.getAnnotation(PermitAll::class.java))
                    .describedAs("%s.%s must NOT be @PermitAll", resource.simpleName, m.name)
                    .isNull()
                assertThat(m.getAnnotation(RolesAllowed::class.java))
                    .describedAs("%s.%s must be @RolesAllowed", resource.simpleName, m.name)
                    .isNotNull()
            }
        }
    }
}
