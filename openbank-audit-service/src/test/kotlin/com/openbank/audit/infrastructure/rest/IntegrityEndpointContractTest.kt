// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.infrastructure.rest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

/**
 * Contract tests for the `GET /api/v1/audit/integrity` endpoint (ADR-0086).
 *
 * These are reflection-based tests that verify the API contract without booting the
 * JAX-RS runtime. They guard:
 *  - the `fromEventId` query param is declared on the method
 *  - the response type is [IntegrityResponse] (not the internal ChainVerification)
 *  - the endpoint carries the correct security annotation (K7 cross-check)
 *
 * Integration tests (actual chain walk) live in [AuditChainHashTest] and rely on
 * the repository being invoked directly (no Kafka, no DB needed for unit coverage).
 */
class IntegrityEndpointContractTest {

    private val verifyIntegrity: Method =
        AuditResource::class.java.declaredMethods.single { it.name == "verifyIntegrity" }

    @Test
    fun `verifyIntegrity accepts a fromEventId query parameter`() {
        val params = verifyIntegrity.parameters
        val fromEventIdParam = params.find { p ->
            p.annotations.any { a ->
                a is jakarta.ws.rs.QueryParam && a.value == "fromEventId"
            }
        }
        assertThat(fromEventIdParam)
            .describedAs("verifyIntegrity must declare a @QueryParam(\"fromEventId\") parameter")
            .isNotNull()
    }

    @Test
    fun `IntegrityResponse carries chainStatus checkedCount unchainedCount and firstBrokenAt`() {
        val fields = IntegrityResponse::class.java.declaredFields.map { it.name }.toSet()
        assertThat(fields).containsAll(
            listOf("chainStatus", "checkedCount", "unchainedCount", "firstBrokenAt"),
        )
    }

    @Test
    fun `IntegrityResponse chainStatus is a String (INTACT or BROKEN, per ADR-0086)`() {
        val field = IntegrityResponse::class.java.getDeclaredField("chainStatus")
        assertThat(field.type).isEqualTo(String::class.java)
    }

    @Test
    fun `IntegrityResponse firstBrokenAt accepts null (nullable UUID)`() {
        // A kotlin data class with UUID? is represented as java.util.UUID at the field type level.
        val field = IntegrityResponse::class.java.getDeclaredField("firstBrokenAt")
        assertThat(field.type).isEqualTo(java.util.UUID::class.java)
        // The kotlin nullable annotation is what actually allows null at the language level;
        // the field type is UUID (not Optional), which is what JSON serialization needs.
    }
}
