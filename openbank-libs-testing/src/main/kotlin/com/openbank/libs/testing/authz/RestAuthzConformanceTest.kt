// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.authz

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
import kotlin.reflect.KClass

/**
 * Shared authz conformance kit (issue #467) generalising the `@PermitAll` reflection guard that
 * was independently copy-pasted into ~25 services (`LedgerSecurityContractTest`,
 * `AmlCaseSecurityTest`, etc. — each re-implementing the identical `isHttpEndpoint()` reflection
 * and the identical two assertions against a locally hardcoded resource class). Pure reflection —
 * `@RolesAllowed`/`@PermitAll` are RUNTIME-retained JAX-RS annotations, so this never boots
 * Quarkus; end-to-end enforcement is still Quarkus OIDC's job at runtime.
 *
 * A consuming service inherits this in one line:
 * ```
 * class MyResourceAuthzConformanceTest : RestAuthzConformanceTest() {
 *     override val resourceClasses = listOf(MyResource::class)
 * }
 * ```
 * Service-specific assertions (which roles a given endpoint must carry) stay local to the
 * service — this kit only enforces the fleet-wide invariant: every endpoint is role-gated, never
 * `@PermitAll`.
 */
// detekt's FunctionNaming excludes **/test/** by default, but these @Test methods must live in
// src/main so testImplementation(project(":openbank-libs-testing")) can pull and inherit them —
// Gradle project dependencies expose a module's main artifact, not its test classes.
@Suppress("FunctionNaming")
abstract class RestAuthzConformanceTest {

    /** Every `@Path`-annotated JAX-RS resource class this service exposes. */
    protected abstract val resourceClasses: List<KClass<*>>

    private val httpVerbs =
        listOf(GET::class.java, POST::class.java, PUT::class.java, DELETE::class.java, PATCH::class.java)

    private fun Method.isHttpEndpoint() = httpVerbs.any { getAnnotation(it) != null }

    private fun endpoints(): List<Method> =
        resourceClasses.flatMap { it.java.declaredMethods.filter { m -> m.isHttpEndpoint() } }

    @Test
    fun `no REST endpoint is PermitAll`() {
        val all = endpoints()
        assertThat(all)
            .describedAs(
                "expected to find HTTP endpoints by reflection across %s",
                resourceClasses.map { it.simpleName },
            )
            .isNotEmpty()
        all.forEach { m ->
            assertThat(m.getAnnotation(PermitAll::class.java))
                .describedAs("%s.%s must NOT be @PermitAll", m.declaringClass.simpleName, m.name)
                .isNull()
        }
    }

    @Test
    fun `every REST endpoint carries RolesAllowed`() {
        endpoints().forEach { m ->
            assertThat(m.getAnnotation(RolesAllowed::class.java))
                .describedAs("%s.%s must be @RolesAllowed", m.declaringClass.simpleName, m.name)
                .isNotNull()
        }
    }
}
