// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.anacredit.infrastructure.rest

import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression guard (C7/ADR-0030): no AnaCredit reporting endpoint must be opened without role-gating.
 * Pure reflection — no Quarkus boot needed.
 */
class AnaCreditSecurityTest {

    @Test
    fun `no AnaCreditResource endpoint is @PermitAll`() {
        val methods = AnaCreditResource::class.java.declaredMethods.filter { m ->
            m.getAnnotation(GET::class.java) != null ||
                m.getAnnotation(POST::class.java) != null ||
                m.getAnnotation(PUT::class.java) != null ||
                m.getAnnotation(DELETE::class.java) != null ||
                m.getAnnotation(PATCH::class.java) != null
        }
        assertThat(methods).isNotEmpty()
        methods.forEach { m ->
            assertThat(m.getAnnotation(PermitAll::class.java))
                .describedAs("${m.name} must not be @PermitAll — use @RolesAllowed")
                .isNull()
        }
    }
}
