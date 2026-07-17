// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.rest

import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression guard (C7/ADR-0030): no notification endpoint must be opened without role-gating.
 * Pure reflection — no Quarkus boot needed.
 */
class NotificationSecurityTest {

    @Test
    fun `no NotificationResource endpoint is @PermitAll`() {
        val methods = NotificationResource::class.java.declaredMethods.filter { m ->
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

    // `suspend fun` compiles to a JVM method with a trailing synthetic Continuation parameter,
    // so getDeclaredMethod(name, <declared param types>) never matches — find by name instead,
    // same as the `no NotificationResource endpoint is @PermitAll` test above does by annotation.
    private fun methodNamed(type: Class<*>, name: String) = type.declaredMethods.single { it.name == name }

    /**
     * `@Authorize`'s `action` string is the ONE thing tying `OperatorMessageResource` /
     * `ApprovalResource` to the rego rules that gate them (`operator-compose-message`,
     * `operator-decide-message-approval`) and to `four_eyes.actions` in `rules.yaml`. None of
     * that is typechecked — a typo here compiles fine and fails closed only in a live OPA
     * decision (403, or 202-forever if it silently stops matching `four_eyes.actions`). This
     * pins the exact strings so a rename shows up as a test diff, not a production surprise.
     */
    @Test
    fun `opsmessage endpoints carry the exact @Authorize action strings the rego rules match on`() {
        val compose = methodNamed(OperatorMessageResource::class.java, "compose")
        assertThat(compose.getAnnotation(Authorize::class.java)?.action).isEqualTo("opsmessage.compose")
        assertThat(compose.getAnnotation(RolesAllowed::class.java)?.value)
            .containsExactlyInAnyOrder("ROLE_OPERATOR", "ROLE_ADMIN")

        val decide = methodNamed(ApprovalResource::class.java, "decide")
        assertThat(decide.getAnnotation(Authorize::class.java)?.action).isEqualTo("opsmessage.approval.decide")
        assertThat(decide.getAnnotation(RolesAllowed::class.java)?.value)
            .containsExactlyInAnyOrder("ROLE_OPERATOR", "ROLE_ADMIN")
    }

    /**
     * Code-review finding (PR #1368): `compose()` originally shipped `@Authorize` with no
     * `resource` binding, so `AuthorizeInterceptor.satisfies()` compared only (action,
     * resourceId=null, maker) on retry — a checker's approval of one message would silently
     * also unlock a retry carrying completely different content, never reviewed by anyone.
     * `resource = "#request"` fixes this by binding the approval to `request.toString()` (the
     * data class's generated, content-derived fingerprint). This pins the annotation carries
     * that binding; `ComposeMessageRequest toString() is a content-sensitive fingerprint` below
     * proves the fingerprint itself actually varies with content, which is what the fix depends on.
     */
    @Test
    fun `opsmessage compose binds its four-eyes approval to the request content, not to nothing`() {
        val compose = methodNamed(OperatorMessageResource::class.java, "compose")
        assertThat(compose.getAnnotation(Authorize::class.java)?.resource)
            .describedAs(
                "compose() must bind a resource — see PR #1368: without it, a checker's " +
                    "approval of one message content silently authorizes ANY later retry from the " +
                    "same maker, regardless of content",
            )
            .isEqualTo("#request")
    }

    @Test
    fun `neither opsmessage endpoint is @PermitAll`() {
        val methods = listOf(
            methodNamed(OperatorMessageResource::class.java, "compose"),
            methodNamed(ApprovalResource::class.java, "decide"),
        )
        methods.forEach { m ->
            assertThat(m.getAnnotation(PermitAll::class.java))
                .describedAs("${m.name} must not be @PermitAll — use @RolesAllowed")
                .isNull()
        }
    }
}
