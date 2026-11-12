// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.infrastructure.rest

import com.openbank.libs.authz.Authorize
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Method

/**
 * ADR-0034 Phase 5: every onboarding endpoint asks the OPA sidecar, and every action it asks
 * about is one `onboarding_rest_ext.rego` actually grants.
 *
 * Both directions matter. An endpoint without `@Authorize` is invisible to the advisory decision
 * log, so the evidence the AUTHZ_ENFORCE flip rests on would silently omit it. An action the rego
 * does not list would be denied the moment enforcement flips, and nothing before the flip says so.
 */
class OnboardingAuthorizeCoverageTest {

    private val resources = listOf(OnboardingResource::class.java, BusinessOnboardingResource::class.java)

    private fun Method.isEndpoint() = listOf(GET::class, POST::class, PUT::class, DELETE::class, PATCH::class)
        .any { getAnnotation(it.java) != null }

    // Kotlin emits a static `$suspendImpl` twin carrying the same annotations for every suspend fun.
    private fun Method.isSuspendTwin() = name.endsWith("\$suspendImpl")

    private val endpoints = resources.flatMap { r ->
        r.declaredMethods.filter { it.isEndpoint() && !it.isSuspendTwin() }
    }

    @Test
    fun `every onboarding endpoint carries @Authorize`() {
        assertThat(endpoints).hasSize(6)
        endpoints.forEach { m ->
            assertThat(m.getAnnotation(Authorize::class.java))
                .describedAs("${m.declaringClass.simpleName}.${m.name} must carry @Authorize")
                .isNotNull()
            assertThat(m.getAnnotation(Authorize::class.java).resource).isEmpty()
        }
    }

    @Test
    fun `every @Authorize action is granted by onboarding_rest_ext rego`() {
        val rego = File("../openbank-infra/gitops/components/onboarding/onboarding_rest_ext.rego").readText()
        val actions = endpoints.map { it.getAnnotation(Authorize::class.java).action }.toSet()
        assertThat(actions).containsExactlyInAnyOrder(
            "onboarding.records.inspect",
            "onboarding.record.inspect",
            "onboarding.funnel.inspect",
            "onboarding.business.cases.inspect",
            "onboarding.business.case.inspect",
            "onboarding.business.funnel.inspect",
        )
        actions.forEach { action ->
            assertThat(rego).describedAs("rego must grant $action").contains("\"$action\"")
        }
    }
}
