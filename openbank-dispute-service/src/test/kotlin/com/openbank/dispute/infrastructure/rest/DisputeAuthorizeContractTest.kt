// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.infrastructure.rest

import com.openbank.libs.authz.Authorize
import jakarta.ws.rs.GET
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

/**
 * Contract guard for the AI-agent read-authorization bridge (ADR-0034, issue #401): every read
 * (`@GET`) endpoint on the dispute + complaint resources must carry an `@Authorize` action so the
 * `query.disputes.readonly` OPA capability has a REST enforcement point to bridge to. A read left
 * un-annotated is exactly the gap #401 tracks — it would silently re-open the bridge hole.
 *
 * Pure reflection (annotations are RUNTIME-retained) — no Quarkus boot. The end-to-end deny/allow
 * behaviour of the bridge itself is asserted in `agents_test.rego`
 * (`test_allow_rest_action_via_disputes_readonly_*`, `test_deny_rest_action_complaint_write_not_bridged`);
 * here we prove the enforcement points those policy tests assume actually exist on the endpoints.
 */
class DisputeAuthorizeContractTest {

    private fun getEndpoints(clazz: Class<*>): List<Method> =
        clazz.declaredMethods.filter { it.getAnnotation(GET::class.java) != null }

    private fun assertReadActions(clazz: Class<*>, allowedPrefixes: Set<String>) {
        val reads = getEndpoints(clazz)
        assertThat(reads).describedAs("expected @GET endpoints on ${clazz.simpleName}").isNotEmpty
        reads.forEach { m ->
            val authorize = m.getAnnotation(Authorize::class.java)
            assertThat(authorize)
                .describedAs("%s.%s (a read) must carry @Authorize (issue #401 bridge)", clazz.simpleName, m.name)
                .isNotNull
            val action = authorize.action
            assertThat(allowedPrefixes.any { action.startsWith("$it.") })
                .describedAs(
                    "%s.%s @Authorize action '%s' must be in %s",
                    clazz.simpleName,
                    m.name,
                    action,
                    allowedPrefixes,
                )
                .isTrue
            // The bridge is read-only (rest_read_verbs): the action must end in a read verb, never a write.
            assertThat(action.substringAfterLast('.'))
                .describedAs("%s.%s @Authorize action '%s' must be a read verb", clazz.simpleName, m.name, action)
                .isIn("read", "list", "search")
        }
    }

    @Test
    fun `every DisputeResource read carries a dispute read-verb Authorize action`() {
        assertReadActions(DisputeResource::class.java, setOf("dispute"))
    }

    @Test
    fun `every ComplaintResource read carries a complaint read-verb Authorize action`() {
        assertReadActions(ComplaintResource::class.java, setOf("complaint"))
    }
}
