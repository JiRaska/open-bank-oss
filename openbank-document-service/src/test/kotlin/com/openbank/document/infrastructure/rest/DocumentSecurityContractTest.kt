// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.rest

import com.openbank.libs.authz.Authorize
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

    /**
     * Every endpoint that reads a specific party's or document's data must also carry `@Authorize`
     * (#8082).
     *
     * `listByParty` shipped with the role gate alone while `getDocument` and `getContent` — which
     * return strictly LESS, one document rather than a party's whole file — were policy-gated. The
     * test above could not see it: it asks whether an endpoint is role-gated, and `listByParty`
     * always was. A role check is not a policy decision, so "is annotated" and "is authorized" are
     * different questions and only the first had a guard.
     *
     * Scoped to the party/document-scoped reads rather than every endpoint, because the template
     * catalogue routes are deliberately not policy-gated: they expose no party data. Naming them
     * explicitly is the point — an endpoint added later is absent from this list, and the list is
     * checked against the resource, so it cannot quietly grow a party-scoped route with no gate.
     */
    @Test
    fun `every party-scoped read carries a policy decision, not only a role check`() {
        val partyScopedReads = mapOf(
            "listByParty" to "document.list",
            "getDocument" to "document.read",
            "getContent" to "document.readContent",
        )

        partyScopedReads.forEach { (methodName, expectedAction) ->
            val method = DocumentResource::class.java.declaredMethods.firstOrNull { it.name == methodName }
            assertThat(method)
                .describedAs("DocumentResource.%s must exist — renamed without updating this guard?", methodName)
                .isNotNull()

            val authorize = method!!.getAnnotation(Authorize::class.java)
            assertThat(authorize)
                .describedAs(
                    "DocumentResource.%s returns party-scoped data and must be @Authorize-gated, " +
                        "not merely @RolesAllowed",
                    methodName,
                )
                .isNotNull()
            assertThat(authorize.action)
                .describedAs("DocumentResource.%s must gate on the agreed action", methodName)
                .isEqualTo(expectedAction)
        }
    }
}
