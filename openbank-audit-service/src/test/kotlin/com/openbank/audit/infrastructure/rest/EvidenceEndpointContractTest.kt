// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.infrastructure.rest

import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Contract for `GET /api/v1/audit/evidence/{aggregateId}` (ADR-0214 D3, #11900), pinned against
 * the published openapi.yaml rather than re-derived from the class, so the two cannot drift.
 */
class EvidenceEndpointContractTest {

    private val method = AuditResource::class.java.declaredMethods.single { it.name == "getEvidence" }
    private val spec = javaClass.classLoader.getResource("openapi.yaml")!!.readText()

    @Test
    fun `route and roles match the contract`() {
        assertThat(method.getAnnotation(Path::class.java).value).isEqualTo("/evidence/{aggregateId}")
        assertThat(spec).contains("  /api/v1/audit/evidence/{aggregateId}:")
        assertThat(method.getAnnotation(RolesAllowed::class.java).value)
            .containsExactlyInAnyOrder("ROLE_AUDITOR", "ROLE_ADMIN", "ROLE_COMPLIANCE", "ROLE_CREDIT_RISK")
    }

    @Test
    fun `the action is outside base rest rego's read verbs, so only the evidence rule can grant it`() {
        val action = method.getAnnotation(Authorize::class.java).action
        assertThat(action).isEqualTo("audit.evidence.reconstruct")
        assertThat(action.substringAfterLast('.')).isNotIn("read", "list")
    }

    @Test
    fun `every response field the spec requires is on the wire type`() {
        val required = listOf(
            "aggregateId",
            "attestation",
            "entryCount",
            "truncated",
            "tampered",
            "hashStatusCounts",
            "fullChainVerification",
            "entries",
        )
        assertThat(EvidenceResponse::class.java.declaredFields.map { it.name }).containsAll(required)
        required.forEach { assertThat(spec).contains(it) }
        assertThat(EvidenceEntryView::class.java.declaredFields.map { it.name })
            .contains("entryId", "eventType", "payload", "recordHash", "prevHash", "hashStatus")
    }
}
