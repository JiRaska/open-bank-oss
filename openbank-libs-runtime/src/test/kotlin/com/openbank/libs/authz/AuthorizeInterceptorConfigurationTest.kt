// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.authz

import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Direct tests for the [AuthorizeInterceptor] configuration surface. Every existing test drives
 * the interceptor through a fully-wired fixture, so the property accessors themselves were never
 * executed: pitest reported the lateinit guards and both boolean toggles as uncovered. These tests
 * read every field: the CDI-injected Instance holders must fail fast (UninitializedPropertyAccess)
 * before injection — a hand-built interceptor that skips wiring must blow up at the read, not
 * carry a null into the decision path — and the toggles must read back both ways, or a config
 * property the operator set could never be observed.
 */
class AuthorizeInterceptorConfigurationTest {

    @Test
    fun `lateinit Instance fields fail fast when read before injection`() {
        val interceptor = AuthorizeInterceptor()
        // Both branches of each lateinit guard: after CDI injection the read succeeds (every
        // interceptor test exercises that); before it, the read must throw — a silent null would
        // surface later as an NPE inside the decision path, with the cause no longer attributable.
        assertThatThrownBy { interceptor.pdp }.isInstanceOf(UninitializedPropertyAccessException::class.java)
        assertThatThrownBy { interceptor.securityContext }
            .isInstanceOf(UninitializedPropertyAccessException::class.java)
        assertThatThrownBy { interceptor.identity }.isInstanceOf(UninitializedPropertyAccessException::class.java)
        assertThatThrownBy { interceptor.httpHeaders }.isInstanceOf(UninitializedPropertyAccessException::class.java)
        assertThatThrownBy { interceptor.clock }.isInstanceOf(UninitializedPropertyAccessException::class.java)
        assertThatThrownBy { interceptor.metrics }.isInstanceOf(UninitializedPropertyAccessException::class.java)
        assertThatThrownBy { interceptor.securityTelemetry }
            .isInstanceOf(UninitializedPropertyAccessException::class.java)
        assertThatThrownBy { interceptor.approvalStore }.isInstanceOf(UninitializedPropertyAccessException::class.java)
    }

    @Test
    fun `boolean toggles read back both ways and the renderer instance round-trips`() {
        val interceptor = AuthorizeInterceptor()
        interceptor.enforce = true
        assertThat(interceptor.enforce).isTrue()
        interceptor.enforce = false
        assertThat(interceptor.enforce).isFalse()
        interceptor.fourEyesEnforce = true
        assertThat(interceptor.fourEyesEnforce).isTrue()
        interceptor.fourEyesEnforce = false
        assertThat(interceptor.fourEyesEnforce).isFalse()

        val renderer = mockk<Instance<ApprovalSummaryRenderer>> {
            every { isResolvable } returns true
        }
        interceptor.summaryRenderer = renderer
        assertThat(interceptor.summaryRenderer).isSameAs(renderer)
    }
}
