// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.infrastructure.rest

import com.openbank.audit.application.AnchorVerification
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Path
import jakarta.ws.rs.QueryParam
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

/**
 * Contract tests for the ADR-0031 D5 anchor endpoints (`GET /api/v1/audit/anchors` and
 * `.../anchors/verify`). Reflection-based — they verify the wire contract and the security
 * annotation (K7: the audit log is never @PermitAll) without booting the JAX-RS runtime.
 */
class AnchorEndpointContractTest {

    private val listAnchors: Method =
        AuditResource::class.java.declaredMethods.single { it.name == "listAnchors" }
    private val verifyAnchors: Method =
        AuditResource::class.java.declaredMethods.single { it.name == "verifyAnchors" }
    private val verificationKey: Method =
        AuditResource::class.java.declaredMethods.single { it.name == "anchorVerificationKey" }

    @Test
    fun `listAnchors accepts a limit query parameter`() {
        val limitParam = listAnchors.parameters.find { p ->
            p.annotations.any { it is QueryParam && it.value == "limit" }
        }
        assertThat(limitParam)
            .describedAs("listAnchors must declare a @QueryParam(\"limit\") parameter")
            .isNotNull()
    }

    @Test
    fun `anchor endpoints are mapped under the documented paths`() {
        assertThat(listAnchors.getAnnotation(Path::class.java).value).isEqualTo("/anchors")
        assertThat(verifyAnchors.getAnnotation(Path::class.java).value).isEqualTo("/anchors/verify")
        assertThat(verificationKey.getAnnotation(Path::class.java).value).isEqualTo("/anchors/verification-key")
    }

    @Test
    fun `verification key lookup requires the recorded key id`() {
        val keyIdParam = verificationKey.parameters.find { parameter ->
            parameter.annotations.any { it is QueryParam && it.value == "keyId" }
        }
        assertThat(keyIdParam).isNotNull()
    }

    @Test
    fun `anchor endpoints are role-gated (never PermitAll, K7)`() {
        for (m in listOf(listAnchors, verifyAnchors, verificationKey)) {
            val roles = m.getAnnotation(RolesAllowed::class.java)
            assertThat(roles)
                .describedAs("%s must be @RolesAllowed — an unauthenticated audit endpoint is a finding", m.name)
                .isNotNull()
            assertThat(roles.value).contains("ROLE_AUDITOR")
        }
    }

    @Test
    fun `AnchorVerification carries the documented summary fields`() {
        val fields = AnchorVerification::class.java.declaredFields.map { it.name }.toSet()
        assertThat(fields).containsAll(
            listOf("status", "anchorCount", "verifiedCount", "unsignedCount", "unverifiableCount", "firstBroken"),
        )
    }
}
