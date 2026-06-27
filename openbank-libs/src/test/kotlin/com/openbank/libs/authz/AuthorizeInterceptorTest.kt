// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.authz

import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import jakarta.interceptor.InvocationContext
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.ServiceUnavailableException
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.SecurityContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.security.Principal as JavaPrincipal

/**
 * Unit tests for [AuthorizeInterceptor]. Verifies roles propagation from
 * SecurityIdentity into the OPA query and the advisory / enforce toggle
 * without standing up a real OPA sidecar.
 */
class AuthorizeInterceptorTest {

    private lateinit var interceptor: AuthorizeInterceptor
    private lateinit var sc: SecurityContext
    private lateinit var identity: SecurityIdentity

    @BeforeEach
    fun setUp() {
        sc = mockk()
        identity = mockk()
        interceptor = AuthorizeInterceptor().apply {
            // securityContext / identity are now Instance<> (lazy) so libs doesn't force a
            // SecurityIdentity bean on non-security services — mirror the pdp wrapping.
            securityContext = mockk { every { get() } returns sc }
            this.identity = mockk { every { get() } returns this@AuthorizeInterceptorTest.identity }
            enforce = true
            httpHeaders = mockk { every { isResolvable } returns false }
        }
        every { sc.userPrincipal } returns JavaPrincipal { "user-42" }
    }

    private fun makeCtx(method: Method, vararg params: Any?): InvocationContext {
        val ctx = mockk<InvocationContext>()
        every { ctx.method } returns method
        every { ctx.parameters } returns arrayOf(*params)
        every { ctx.proceed() } returns "ok"
        return ctx
    }

    @Authorize(action = "party.read")
    fun dummyMethod() = Unit

    @Authorize(action = "payment.create", attributes = ["time-of-day", "client-ip"])
    fun dummyMethodWithAttrs() = Unit

    private val annotatedMethod: Method =
        AuthorizeInterceptorTest::class.java.getDeclaredMethod("dummyMethod")

    private val annotatedMethodWithAttrs: Method =
        AuthorizeInterceptorTest::class.java.getDeclaredMethod("dummyMethodWithAttrs")

    @Test
    fun `JWT roles propagated into OPA query`() {
        every { identity.roles } returns setOf("ROLE_OPERATOR", "ROLE_ADMIN")
        val capturedQuery = mutableListOf<AuthzQuery>()
        val pdp = object : PolicyDecisionPoint {
            override suspend fun allow(query: AuthzQuery): AuthzDecision {
                capturedQuery += query
                return AuthzDecision(allow = true, reason = "ok", policyVersion = "test")
            }
        }
        interceptor.pdp = mockk {
            every { isResolvable } returns true
            every { get() } returns pdp
        }
        val ctx = makeCtx(annotatedMethod)
        interceptor.authorize(ctx)
        assertThat(capturedQuery).hasSize(1)
        assertThat(capturedQuery[0].principal.roles)
            .containsExactlyInAnyOrder("ROLE_OPERATOR", "ROLE_ADMIN")
    }

    @Test
    fun `enforce=true, PDP deny throws 403`() {
        every { identity.roles } returns emptySet()
        val pdp = object : PolicyDecisionPoint {
            override suspend fun allow(query: AuthzQuery): AuthzDecision =
                AuthzDecision(allow = false, reason = "insufficient role", policyVersion = "v1")
        }
        interceptor.pdp = mockk {
            every { isResolvable } returns true
            every { get() } returns pdp
        }
        val ctx = makeCtx(annotatedMethod)
        assertThatThrownBy { interceptor.authorize(ctx) }
            .isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `enforce=false, PDP deny proceeds without throwing`() {
        interceptor.enforce = false
        every { identity.roles } returns emptySet()
        val pdp = object : PolicyDecisionPoint {
            override suspend fun allow(query: AuthzQuery): AuthzDecision =
                AuthzDecision(allow = false, reason = "insufficient role", policyVersion = "v1")
        }
        interceptor.pdp = mockk {
            every { isResolvable } returns true
            every { get() } returns pdp
        }
        val ctx = makeCtx(annotatedMethod)
        val result = interceptor.authorize(ctx)
        assertThat(result).isEqualTo("ok")
    }

    @Test
    fun `enforce=true, no PDP bean throws 503`() {
        every { identity.roles } returns emptySet()
        interceptor.pdp = mockk { every { isResolvable } returns false }
        val ctx = makeCtx(annotatedMethod)
        assertThatThrownBy { interceptor.authorize(ctx) }
            .isInstanceOf(ServiceUnavailableException::class.java)
    }

    @Test
    fun `principal type ANONYMOUS when no userPrincipal`() {
        every { sc.userPrincipal } returns null
        every { identity.roles } returns emptySet()
        val capturedQuery = mutableListOf<AuthzQuery>()
        val pdp = object : PolicyDecisionPoint {
            override suspend fun allow(query: AuthzQuery): AuthzDecision {
                capturedQuery += query
                return AuthzDecision(allow = true, reason = "ok", policyVersion = "test")
            }
        }
        interceptor.pdp = mockk {
            every { isResolvable } returns true
            every { get() } returns pdp
        }
        interceptor.authorize(makeCtx(annotatedMethod))
        assertThat(capturedQuery[0].principal.type).isEqualTo("ANONYMOUS")
        assertThat(capturedQuery[0].principal.id).isEqualTo("anonymous")
    }

    @Test
    fun `principal type AI_AGENT when sub starts with agent colon`() {
        every { sc.userPrincipal } returns JavaPrincipal { "agent:onboarding" }
        every { identity.roles } returns setOf("ROLE_AGENT")
        val capturedQuery = mutableListOf<AuthzQuery>()
        val pdp = object : PolicyDecisionPoint {
            override suspend fun allow(query: AuthzQuery): AuthzDecision {
                capturedQuery += query
                return AuthzDecision(allow = true, reason = "ok", policyVersion = "test")
            }
        }
        interceptor.pdp = mockk {
            every { isResolvable } returns true
            every { get() } returns pdp
        }
        interceptor.authorize(makeCtx(annotatedMethod))
        assertThat(capturedQuery[0].principal.type).isEqualTo("AI_AGENT")
        assertThat(capturedQuery[0].principal.roles).containsExactly("ROLE_AGENT")
    }

    @Test
    fun `@Authorize attributes forwarded to AuthzQuery when HttpHeaders available`() {
        every { identity.roles } returns setOf("ROLE_OPERATOR")
        val capturedQuery = mutableListOf<AuthzQuery>()
        val pdp = object : PolicyDecisionPoint {
            override suspend fun allow(query: AuthzQuery): AuthzDecision {
                capturedQuery += query
                return AuthzDecision(allow = true)
            }
        }
        interceptor.pdp = mockk {
            every { isResolvable } returns true
            every { get() } returns pdp
        }
        val fakeHeaders = mockk<HttpHeaders> {
            every { getRequestHeader("X-Forwarded-For") } returns listOf("10.0.0.1")
            every { getRequestHeader("Idempotency-Key") } returns null
        }
        interceptor.httpHeaders = mockk {
            every { isResolvable } returns true
            every { get() } returns fakeHeaders
        }
        interceptor.authorize(makeCtx(annotatedMethodWithAttrs))
        val attrs = capturedQuery[0].attributes
        assertThat(attrs).containsKey("time-of-day")
        assertThat(attrs["client-ip"]).isEqualTo("10.0.0.1")
    }

    @Test
    fun `only header-independent attributes resolve when no HttpHeaders context`() {
        // setUp() wires httpHeaders.isResolvable = false. `time-of-day` is the wall
        // clock (Authorize.kt: "time-of-day → ISO instant"), so it resolves without
        // an HTTP request; `client-ip` is header-derived and must drop out.
        every { identity.roles } returns emptySet()
        val capturedQuery = mutableListOf<AuthzQuery>()
        val pdp = object : PolicyDecisionPoint {
            override suspend fun allow(query: AuthzQuery): AuthzDecision {
                capturedQuery += query
                return AuthzDecision(allow = true)
            }
        }
        interceptor.pdp = mockk {
            every { isResolvable } returns true
            every { get() } returns pdp
        }
        interceptor.authorize(makeCtx(annotatedMethodWithAttrs))
        val attrs = capturedQuery[0].attributes
        assertThat(attrs).containsOnlyKeys("time-of-day")
        assertThat(attrs).doesNotContainKey("client-ip")
    }
}
