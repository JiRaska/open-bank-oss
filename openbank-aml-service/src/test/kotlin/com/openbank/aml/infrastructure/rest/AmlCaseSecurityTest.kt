// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.testing.authz.RestAuthzConformanceTest
import jakarta.ws.rs.GET
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression guard (C7/ADR-0030): no AML endpoint must be opened without role-gating. Migrated
 * to the shared `openbank-libs-testing` kit (issue #467) — stricter than the original hand-rolled
 * version it replaces, which only checked for an explicit `@PermitAll` and missed that
 * `getCase`/`listCases` had NO security annotation at all. Quarkus treats an unannotated JAX-RS
 * method as unauthenticated-accessible by default (`quarkus.security.jaxrs.deny-unannotated-
 * endpoints` defaults to `false`, unset anywhere in this fleet) — so those two endpoints were
 * live and open with no role check. Fixed alongside this migration.
 */
class AmlCaseSecurityTest : RestAuthzConformanceTest() {
    override val resourceClasses = listOf(AmlCaseResource::class)

    @Test
    fun `AML reads carry an amlCase read-verb Authorize action for the AI-agent bridge`() {
        val reads = AmlCaseResource::class.java.declaredMethods.filter { it.getAnnotation(GET::class.java) != null }
        assertThat(reads).describedAs("expected @GET reads on AmlCaseResource").isNotEmpty
        reads.forEach { m ->
            val authorize = m.getAnnotation(Authorize::class.java)
            assertThat(authorize)
                .describedAs("%s (a read) must carry @Authorize (issue #401 bridge)", m.name)
                .isNotNull
            assertThat(authorize.action)
                .describedAs("%s @Authorize action must be a read verb under amlCase", m.name)
                .isIn("amlCase.read", "amlCase.list", "amlCase.search")
        }
    }
}
