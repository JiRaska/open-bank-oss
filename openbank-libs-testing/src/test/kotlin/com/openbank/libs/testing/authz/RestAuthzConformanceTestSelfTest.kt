// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.authz

import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

private class CompliantResource {
    @GET
    @RolesAllowed("ROLE_VIEWER")
    fun read(): Unit = Unit

    @POST
    @RolesAllowed("ROLE_OPERATOR")
    fun write(): Unit = Unit
}

private class PermitAllResource {
    @GET
    @PermitAll
    fun read(): Unit = Unit
}

private class UnannotatedResource {
    @GET
    fun read(): Unit = Unit
}

private class CompliantConformanceTest : RestAuthzConformanceTest() {
    override val resourceClasses = listOf(CompliantResource::class)
}

// @Disabled: these two intentionally violate the rule (that's what the self-test below exercises
// via direct method calls) — without it, JUnit5's classpath scan also auto-discovers and runs
// them as their own top-level test classes, which then genuinely fail the build.
@Disabled("fixture for RestAuthzConformanceTestSelfTest — intentionally non-compliant")
private class PermitAllConformanceTest : RestAuthzConformanceTest() {
    override val resourceClasses = listOf(PermitAllResource::class)
}

@Disabled("fixture for RestAuthzConformanceTestSelfTest — intentionally non-compliant")
private class UnannotatedConformanceTest : RestAuthzConformanceTest() {
    override val resourceClasses = listOf(UnannotatedResource::class)
}

/** Proves the kit itself actually catches what it claims to, before any service inherits it. */
class RestAuthzConformanceTestSelfTest {

    @Test
    fun `passes for a fully role-gated resource`() {
        val test = CompliantConformanceTest()
        test.`no REST endpoint is PermitAll`()
        test.`every REST endpoint carries RolesAllowed`()
    }

    @Test
    fun `fails for a PermitAll endpoint`() {
        assertThatThrownBy { PermitAllConformanceTest().`no REST endpoint is PermitAll`() }
            .isInstanceOf(AssertionError::class.java)
    }

    @Test
    fun `fails for an endpoint missing RolesAllowed`() {
        assertThatThrownBy { UnannotatedConformanceTest().`every REST endpoint carries RolesAllowed`() }
            .isInstanceOf(AssertionError::class.java)
    }

    @Test
    fun `fails fast when a resource class has no HTTP-verb-annotated methods`() {
        class Empty
        class EmptyConformanceTest : RestAuthzConformanceTest() {
            override val resourceClasses = listOf(Empty::class)
        }
        assertThatThrownBy { EmptyConformanceTest().`no REST endpoint is PermitAll`() }
            .isInstanceOf(AssertionError::class.java)
    }
}
