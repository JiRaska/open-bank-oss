// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.rest

import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression guard (C7/ADR-0030): no SWIFT endpoint must be opened without role-gating.
 * SWIFT is a money-path service — unauthorised access would expose cross-border payment operations.
 * Pure reflection — no Quarkus boot needed.
 */
class SwiftSecurityTest {

    @Test
    fun `no SwiftResource endpoint is @PermitAll`() {
        val methods = SwiftResource::class.java.declaredMethods.filter { m ->
            m.getAnnotation(GET::class.java) != null ||
                m.getAnnotation(POST::class.java) != null ||
                m.getAnnotation(PUT::class.java) != null ||
                m.getAnnotation(DELETE::class.java) != null ||
                m.getAnnotation(PATCH::class.java) != null
        }
        assertThat(methods).isNotEmpty()
        methods.forEach { m ->
            assertThat(m.getAnnotation(PermitAll::class.java))
                .describedAs("${m.name} must not be @PermitAll — money-path: use @RolesAllowed")
                .isNull()
        }
    }
}
