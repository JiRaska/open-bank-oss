// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.rest

import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #10486 batch 7: the three SCT Inst reads admit ROLE_API (agent-service's own principal), and the two
 * writes do not. ROLE_API is held by every service account, so each ROLE_API endpoint must be decided
 * by an OPA action, which grants it by identity (`service-agent-sct-inst-read`).
 */
class SctInstMachineReadRbacTest {

    private val methods = SctInstResource::class.java.declaredMethods
        .filter { it.getAnnotation(RolesAllowed::class.java) != null }

    @Test
    fun `exactly the three reads admit ROLE_API`() {
        val withApi = methods.filter { "ROLE_API" in it.getAnnotation(RolesAllowed::class.java).value }
            .map { it.name }.toSet()
        assertThat(withApi).isEqualTo(setOf("listAll", "getById", "listByDebtor"))
    }

    @Test
    fun `every ROLE_API endpoint carries a read-family OPA action`() {
        methods.filter { "ROLE_API" in it.getAnnotation(RolesAllowed::class.java).value }.forEach { m ->
            val action = m.getAnnotation(Authorize::class.java)?.action
            assertThat(action).describedAs(m.name).isIn("sctInstPayment.list", "sctInstPayment.read")
        }
    }
}
