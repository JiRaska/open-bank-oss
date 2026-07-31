// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.infrastructure.rest

import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression guard for K7: the audit trail must never be unauthenticated.
 *
 * `@RolesAllowed` / `@PermitAll` are RUNTIME-retained, so we can assert the access-control
 * contract by reflection without booting the JAX-RS runtime (audit-service has no @QuarkusTest
 * scaffold). End-to-end enforcement is Quarkus' responsibility; this locks the declarative
 * contract so a silent revert to @PermitAll fails the build.
 */
class AuditResourceSecurityTest {

    private val getAuditTrail =
        AuditResource::class.java.declaredMethods.single { it.name == "getAuditTrail" }

    private val verifyIntegrity =
        AuditResource::class.java.declaredMethods.single { it.name == "verifyIntegrity" }

    private val getActorTrail =
        AuditResource::class.java.declaredMethods.single { it.name == "getActorTrail" }

    @Test
    fun `by-actor endpoint is role-gated, never permit-all (ADR-0226 — the person query is the most sensitive one)`() {
        assertThat(getActorTrail.getAnnotation(PermitAll::class.java))
            .describedAs("by-actor trail must not be @PermitAll")
            .isNull()

        assertThat(getActorTrail.getAnnotation(RolesAllowed::class.java))
            .describedAs("by-actor trail must be @RolesAllowed")
            .isNotNull()
    }

    @Test
    fun `by-actor endpoint is restricted to the audit-reading roles`() {
        val roles = getActorTrail.getAnnotation(RolesAllowed::class.java).value.toList()

        assertThat(roles).containsExactlyInAnyOrder(
            "ROLE_AUDITOR",
            "ROLE_ADMIN",
            "ROLE_COMPLIANCE",
        )
    }

    @Test
    fun `audit trail endpoint is role-gated, never permit-all`() {
        assertThat(getAuditTrail.getAnnotation(PermitAll::class.java))
            .describedAs("audit trail must not be @PermitAll (K7)")
            .isNull()

        assertThat(getAuditTrail.getAnnotation(RolesAllowed::class.java))
            .describedAs("audit trail must be @RolesAllowed")
            .isNotNull()
    }

    @Test
    fun `audit trail is restricted to the audit-reading roles`() {
        val roles = getAuditTrail.getAnnotation(RolesAllowed::class.java).value.toList()

        assertThat(roles).containsExactlyInAnyOrder(
            "ROLE_AUDITOR",
            "ROLE_ADMIN",
            "ROLE_COMPLIANCE",
        )
    }

    @Test
    fun `integrity endpoint is role-gated, never permit-all (K7)`() {
        assertThat(verifyIntegrity.getAnnotation(PermitAll::class.java))
            .describedAs("integrity endpoint must not be @PermitAll (K7)")
            .isNull()

        assertThat(verifyIntegrity.getAnnotation(RolesAllowed::class.java))
            .describedAs("integrity endpoint must be @RolesAllowed")
            .isNotNull()
    }

    @Test
    fun `integrity endpoint is restricted to the audit-reading roles`() {
        val roles = verifyIntegrity.getAnnotation(RolesAllowed::class.java).value.toList()

        assertThat(roles).containsExactlyInAnyOrder(
            "ROLE_AUDITOR",
            "ROLE_ADMIN",
            "ROLE_COMPLIANCE",
        )
    }
}
