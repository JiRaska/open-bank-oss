// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.infrastructure.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

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

    private val getCustomerAccessLog =
        AuditResource::class.java.declaredMethods.single { it.name == "getCustomerAccessLog" }

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

    // ── Customer privacy view (P2-27) ────────────────────────────────────────────────────────

    /**
     * The endpoint is reached ONLY by customer-edge, which authenticates as the `openbank-edge`
     * client (UpstreamClient). Its service account's realm roles are declared in the Keycloak
     * template, so the two must intersect or every call 403s at the JAX-RS layer before the PDP
     * is ever consulted — which is exactly how this shipped first: @RolesAllowed(ROLE_API)
     * against a service account holding only ROLE_OPERATOR. Read BOTH sides rather than pinning
     * a literal, so a realm-side role change fails here too.
     */
    @Test
    fun `the customer access log admits the role customer-edge's service account actually holds`() {
        val declared = getCustomerAccessLog.getAnnotation(RolesAllowed::class.java).value.toSet()
        val edgeRoles = edgeServiceAccountRealmRoles()

        assertThat(edgeRoles)
            .describedAs("service-account-openbank-edge must exist in the realm template with roles")
            .isNotEmpty()
        assertThat(declared.intersect(edgeRoles))
            .describedAs("customer-edge (%s) cannot reach @RolesAllowed%s", edgeRoles, declared)
            .isNotEmpty()
    }

    /**
     * ROLE_OPERATOR is also held by real staff, so the widened @RolesAllowed is only safe because
     * OPA narrows it: the action must stay DISTINCT from the auditor-facing `audit.read`, whose
     * rest.rego grant (operator-read-any, matching the `.read` suffix) would otherwise admit any
     * operator to a customer's trail.
     */
    @Test
    fun `the customer access log carries its own authz action, not the auditor-facing audit read`() {
        val action = getCustomerAccessLog.getAnnotation(Authorize::class.java).action

        assertThat(action).isEqualTo("audit.customerRead")
        assertThat(action).doesNotEndWith(".read")
    }

    @Test
    fun `the customer access log is role-gated, never permit-all (K7)`() {
        assertThat(getCustomerAccessLog.getAnnotation(PermitAll::class.java)).isNull()
        assertThat(getCustomerAccessLog.getAnnotation(RolesAllowed::class.java)).isNotNull()
    }

    private fun edgeServiceAccountRealmRoles(): Set<String> {
        val template = generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, "openbank-infra/gitops/components/keycloak/realm-template.json") }
            .firstOrNull { it.isFile }
        assertThat(template).describedAs("realm-template.json not found from %s", File(".").absolutePath).isNotNull()

        val realm = jacksonObjectMapper().readTree(template!!)
        val user = realm["users"].first { it["username"]?.asText() == "service-account-openbank-edge" }
        return user["realmRoles"].map { it.asText() }.toSet()
    }
}
