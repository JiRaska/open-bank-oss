// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.authz

import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.InMemoryApprovalStore
import com.openbank.libs.approval.InvalidApprovalStateException
import com.openbank.libs.observability.DomainMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import jakarta.interceptor.InvocationContext
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.SecurityContext
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
    private lateinit var registry: SimpleMeterRegistry

    /** Counter value for the given name+tags, or 0.0 when the meter was never created. */
    private fun counter(name: String, vararg tags: String): Double =
        registry.find(name).tags(*tags).counter()?.count() ?: 0.0

    @BeforeEach
    fun setUp() {
        sc = mockk()
        identity = mockk()
        // A real registry (not a mock) so the tests assert the metric a scrape would actually see,
        // tags included — a mock would only prove the interceptor called a method.
        registry = SimpleMeterRegistry()
        val domainMetrics = DomainMetrics().apply {
            registryInstance = mockk {
                every { isResolvable } returns true
                every { get() } returns this@AuthorizeInterceptorTest.registry
            }
        }
        interceptor = AuthorizeInterceptor().apply {
            metrics = mockk {
                every { isResolvable } returns true
                every { get() } returns domainMetrics
            }
            // securityContext / identity are now Instance<> (lazy) so libs doesn't force a
            // SecurityIdentity bean on non-security services — mirror the pdp wrapping.
            securityContext = mockk { every { get() } returns sc }
            this.identity = mockk { every { get() } returns this@AuthorizeInterceptorTest.identity }
            enforce = true
            fourEyesEnforce = false
            approvalStore = mockk { every { isResolvable } returns false }
            httpHeaders = mockk { every { isResolvable } returns false }
            // ADR-0100: the interceptor now reads the wall clock through an injected Clock
            // (resolveAttributes: "time-of-day" → Instant.now(clock)). A fixed clock keeps the
            // attribute-forwarding assertions deterministic.
            clock = Clock.fixed(Instant.parse("2026-06-22T10:20:00Z"), ZoneOffset.UTC)
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

    data class DummyRequest(val granteeId: String, val scopes: List<String>)

    data class DummyRequestWithNullableField(val granteeId: String?)

    @Suppress("UnusedParameter") // invoked only via InvocationContext reflection, not directly
    @Authorize(action = "consent.grant", resource = "#request.granteeId")
    fun dummyMethodWithDottedResource(request: DummyRequest?) = Unit

    @Suppress("UnusedParameter") // invoked only via InvocationContext reflection, not directly
    @Authorize(action = "consent.grant", resource = "#request.doesNotExist")
    fun dummyMethodWithUnknownField(request: DummyRequest?) = Unit

    @Suppress("UnusedParameter") // invoked only via InvocationContext reflection, not directly
    @Authorize(action = "consent.grant", resource = "#request.granteeId")
    fun dummyMethodWithNullableFieldResource(request: DummyRequestWithNullableField?) = Unit

    private val annotatedMethod: Method =
        AuthorizeInterceptorTest::class.java.getDeclaredMethod("dummyMethod")

    private val annotatedMethodWithAttrs: Method =
        AuthorizeInterceptorTest::class.java.getDeclaredMethod("dummyMethodWithAttrs")

    private val annotatedMethodWithDottedResource: Method =
        AuthorizeInterceptorTest::class.java.getDeclaredMethod(
            "dummyMethodWithDottedResource",
            DummyRequest::class.java,
        )

    private val annotatedMethodWithUnknownField: Method =
        AuthorizeInterceptorTest::class.java.getDeclaredMethod(
            "dummyMethodWithUnknownField",
            DummyRequest::class.java,
        )

    private val annotatedMethodWithNullableFieldResource: Method =
        AuthorizeInterceptorTest::class.java.getDeclaredMethod(
            "dummyMethodWithNullableFieldResource",
            DummyRequestWithNullableField::class.java,
        )

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
    fun `dotted-path resource expression resolves the named field, not the whole request`() {
        every { identity.roles } returns emptySet()
        val capturedQuery = mutableListOf<AuthzQuery>()
        val pdp = object : PolicyDecisionPoint {
            override suspend fun allow(query: AuthzQuery): AuthzDecision {
                capturedQuery += query
                return AuthzDecision(allow = true, reason = "ok", policyVersion = "test")
            }
        }
        wirePdp(pdp)
        val ctx =
            makeCtx(
                annotatedMethodWithDottedResource,
                DummyRequest(granteeId = "party-service:marketing-comms", scopes = listOf("x")),
            )
        interceptor.authorize(ctx)
        assertThat(capturedQuery).hasSize(1)
        assertThat(capturedQuery[0].resource?.id).isEqualTo("party-service:marketing-comms")
        assertThat(capturedQuery[0].resource?.type).isEqualTo("consent")
    }

    @Test
    fun `dotted-path resource expression with unknown field fails closed to no resource, not a crash`() {
        every { identity.roles } returns emptySet()
        val capturedQuery = mutableListOf<AuthzQuery>()
        val pdp = object : PolicyDecisionPoint {
            override suspend fun allow(query: AuthzQuery): AuthzDecision {
                capturedQuery += query
                return AuthzDecision(allow = true, reason = "ok", policyVersion = "test")
            }
        }
        wirePdp(pdp)
        val ctx = makeCtx(
            annotatedMethodWithUnknownField,
            DummyRequest(granteeId = "party-service:marketing-comms", scopes = listOf("x")),
        )
        interceptor.authorize(ctx)
        assertThat(capturedQuery).hasSize(1)
        assertThat(capturedQuery[0].resource).isNull()
    }

    @Test
    fun `dotted-path resource expression with a null param fails closed to no resource`() {
        every { identity.roles } returns emptySet()
        val capturedQuery = mutableListOf<AuthzQuery>()
        wirePdp(
            object : PolicyDecisionPoint {
                override suspend fun allow(query: AuthzQuery): AuthzDecision {
                    capturedQuery += query
                    return AuthzDecision(allow = true, reason = "ok", policyVersion = "test")
                }
            },
        )
        val ctx = makeCtx(annotatedMethodWithDottedResource, null)
        interceptor.authorize(ctx)
        assertThat(capturedQuery).hasSize(1)
        assertThat(capturedQuery[0].resource).isNull()
    }

    @Test
    fun `dotted-path resource expression whose field resolves to null fails closed to no resource`() {
        every { identity.roles } returns emptySet()
        val capturedQuery = mutableListOf<AuthzQuery>()
        wirePdp(
            object : PolicyDecisionPoint {
                override suspend fun allow(query: AuthzQuery): AuthzDecision {
                    capturedQuery += query
                    return AuthzDecision(allow = true, reason = "ok", policyVersion = "test")
                }
            },
        )
        val ctx = makeCtx(annotatedMethodWithNullableFieldResource, DummyRequestWithNullableField(granteeId = null))
        interceptor.authorize(ctx)
        assertThat(capturedQuery).hasSize(1)
        assertThat(capturedQuery[0].resource).isNull()
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

    // ── openbank.authz.decisions (ADR-0034 D5 rollout signal) ────────────────
    //
    // Each of these fails on the pre-metric interceptor, where the only advisory signal was a WARN
    // line on stdout. That absence is what made every service's stated rollout precondition ("flip
    // to true only after an observation window with a clean advisory report") unevaluable.

    private fun denyingPdp() = object : PolicyDecisionPoint {
        override suspend fun allow(query: AuthzQuery): AuthzDecision =
            AuthzDecision(allow = false, reason = "insufficient role", policyVersion = "v1")
    }

    private fun allowingPdp(attributes: Map<String, Any> = emptyMap()) = object : PolicyDecisionPoint {
        override suspend fun allow(query: AuthzQuery): AuthzDecision =
            AuthzDecision(allow = true, reason = "ok", policyVersion = "v1", attributes = attributes)
    }

    private fun wirePdp(pdp: PolicyDecisionPoint) {
        interceptor.pdp = mockk {
            every { isResolvable } returns true
            every { get() } returns pdp
        }
    }

    @Test
    fun `advisory deny is counted as would-DENY, tagged enforced=false`() {
        interceptor.enforce = false
        every { identity.roles } returns emptySet()
        wirePdp(denyingPdp())

        assertThat(interceptor.authorize(makeCtx(annotatedMethod))).isEqualTo("ok")

        // This exact series is the "advisory report" a rollout needs empty before flipping.
        assertThat(
            counter(
                "openbank.authz.decisions",
                "action", "party.read", "outcome", "deny",
                "enforced", "false", "principal_type", "HUMAN",
            ),
        ).isEqualTo(1.0)
    }

    @Test
    fun `enforced deny is counted separately from an advisory deny`() {
        every { identity.roles } returns emptySet()
        wirePdp(denyingPdp())

        assertThatThrownBy { interceptor.authorize(makeCtx(annotatedMethod)) }
            .isInstanceOf(ForbiddenException::class.java)

        assertThat(counter("openbank.authz.decisions", "outcome", "deny", "enforced", "true"))
            .isEqualTo(1.0)
        // The enforced=false series must stay untouched, or the two populations are conflated and
        // the whole rollout signal is worthless.
        assertThat(counter("openbank.authz.decisions", "outcome", "deny", "enforced", "false"))
            .isZero()
    }

    @Test
    fun `allow is counted`() {
        every { identity.roles } returns setOf("ROLE_OPERATOR")
        wirePdp(allowingPdp())

        interceptor.authorize(makeCtx(annotatedMethod))

        assertThat(counter("openbank.authz.decisions", "outcome", "allow", "enforced", "true"))
            .isEqualTo(1.0)
    }

    @Test
    fun `missing PDP bean is counted as pdp_unconfigured`() {
        every { identity.roles } returns emptySet()
        interceptor.pdp = mockk { every { isResolvable } returns false }

        assertThatThrownBy { interceptor.authorize(makeCtx(annotatedMethod)) }
            .isInstanceOf(PolicyDecisionException::class.java)

        assertThat(counter("openbank.authz.decisions", "outcome", "pdp_unconfigured"))
            .isEqualTo(1.0)
    }

    @Test
    fun `four_eyes_required with enforcement off is counted, not silently dropped`() {
        // The fleet's current state: every service that declares the key sets
        // ${AUTHZ_FOUR_EYES_ENFORCE:false} and gitops never overrides it. OPA computes the flag and
        // the interceptor proceeds anyway — previously with no signal whatsoever, so a real
        // maker-checker gap looked identical to "no action is flagged".
        interceptor.fourEyesEnforce = false
        every { identity.roles } returns setOf("ROLE_OPERATOR")
        wirePdp(allowingPdp(mapOf("four_eyes_required" to true)))

        assertThat(interceptor.authorize(makeCtx(annotatedMethod))).isEqualTo("ok")

        assertThat(
            counter("openbank.authz.four_eyes", "action", "party.read", "outcome", "required_not_enforced"),
        ).isEqualTo(1.0)
    }

    @Test
    fun `an allow with no four-eyes flag records no four_eyes series`() {
        // Guards the counter's meaning: if it fired on every allow, a non-zero
        // required_not_enforced would say nothing about a real gap.
        interceptor.fourEyesEnforce = false
        every { identity.roles } returns setOf("ROLE_OPERATOR")
        wirePdp(allowingPdp())

        interceptor.authorize(makeCtx(annotatedMethod))

        assertThat(registry.find("openbank.authz.four_eyes").counters()).isEmpty()
    }

    @Test
    fun `enforce=true, no PDP bean throws 503`() {
        every { identity.roles } returns emptySet()
        interceptor.pdp = mockk { every { isResolvable } returns false }
        val ctx = makeCtx(annotatedMethod)
        assertThatThrownBy { interceptor.authorize(ctx) }
            .isInstanceOf(PolicyDecisionException::class.java)
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

    // ---------------------------------------------------------------------------------------
    // Four-eyes gate (ADR-0155, issue #395). fourEyesPdp() simulates an OPA decision that
    // already carries attributes.four_eyes_required=true — the rego-side matching itself is
    // covered by rest_test.rego, this only exercises the interceptor's handling of the flag.
    // ---------------------------------------------------------------------------------------

    private fun fourEyesPdp(): PolicyDecisionPoint = object : PolicyDecisionPoint {
        override suspend fun allow(query: AuthzQuery): AuthzDecision =
            AuthzDecision(allow = true, reason = "ok", attributes = mapOf("four_eyes_required" to true))
    }

    private fun wirePdpAndStore(store: ApprovalStore, enforce: Boolean = true) {
        interceptor.pdp = mockk {
            every { isResolvable } returns true
            every { get() } returns fourEyesPdp()
        }
        interceptor.fourEyesEnforce = enforce
        interceptor.approvalStore = mockk {
            every { isResolvable } returns true
            every { get() } returns store
        }
    }

    @Test
    fun `four-eyes required but fourEyesEnforce=false proceeds unchanged (safe default)`() {
        every { identity.roles } returns setOf("ROLE_OPERATOR")
        wirePdpAndStore(InMemoryApprovalStore(), enforce = false)
        val result = interceptor.authorize(makeCtx(annotatedMethod))
        assertThat(result).isEqualTo("ok")
    }

    @Test
    fun `four-eyes required and enforced but no ApprovalStore wired proceeds unchanged`() {
        every { identity.roles } returns setOf("ROLE_OPERATOR")
        interceptor.pdp = mockk {
            every { isResolvable } returns true
            every { get() } returns fourEyesPdp()
        }
        interceptor.fourEyesEnforce = true
        interceptor.approvalStore = mockk { every { isResolvable } returns false }
        val result = interceptor.authorize(makeCtx(annotatedMethod))
        assertThat(result).isEqualTo("ok")
    }

    @Test
    fun `four-eyes enforced, no approval id header, creates a pending approval and returns 202`() {
        every { identity.roles } returns setOf("ROLE_OPERATOR")
        val store = InMemoryApprovalStore()
        wirePdpAndStore(store)

        val thrown = catchThrowableOfType(
            WebApplicationException::class.java,
        ) { interceptor.authorize(makeCtx(annotatedMethod)) }
        assertThat(thrown.response.status).isEqualTo(202)
        assertThat(store.created).hasSize(1)
        assertThat(store.created[0].makerId).isEqualTo("user-42")
        assertThat(store.created[0].action).isEqualTo("party.read")
    }

    @Test
    fun `four-eyes enforced, valid approved approval id proceeds and consumes it`() {
        every { identity.roles } returns setOf("ROLE_OPERATOR")
        val store = InMemoryApprovalStore()
        wirePdpAndStore(store)

        val pending = runBlocking { store.create("party.read", null, "user-42") }
        runBlocking { store.decide(pending.id, "checker-99", approve = true) }
        interceptor.httpHeaders = mockk {
            every { isResolvable } returns true
            every { get() } returns mockk { every { getRequestHeader("X-Approval-Id") } returns listOf(pending.id) }
        }

        val result = interceptor.authorize(makeCtx(annotatedMethod))
        assertThat(result).isEqualTo("ok")
        assertThat(runBlocking { store.find(pending.id) }?.status).isEqualTo(ApprovalStatus.EXECUTED)
    }

    @Test
    fun `code review fix - decide on an already-EXECUTED approval throws instead of replaying it`() {
        // Regression test for the replay bug: decide() used to unconditionally overwrite
        // status, so re-deciding an EXECUTED approval flipped it back to APPROVED and let the
        // maker replay the same X-Approval-Id to execute the gated action a second time.
        every { identity.roles } returns setOf("ROLE_OPERATOR")
        val store = InMemoryApprovalStore()
        wirePdpAndStore(store)

        val pending = runBlocking { store.create("party.read", null, "user-42") }
        runBlocking { store.decide(pending.id, "checker-99", approve = true) }
        interceptor.httpHeaders = mockk {
            every { isResolvable } returns true
            every { get() } returns mockk { every { getRequestHeader("X-Approval-Id") } returns listOf(pending.id) }
        }
        interceptor.authorize(makeCtx(annotatedMethod)) // consumes it: APPROVED -> EXECUTED

        assertThatThrownBy { runBlocking { store.decide(pending.id, "checker-100", approve = true) } }
            .isInstanceOf(InvalidApprovalStateException::class.java)
        assertThat(runBlocking { store.find(pending.id) }?.status).isEqualTo(ApprovalStatus.EXECUTED)
    }

    @Test
    fun `code review fix - markExecuted throws when the approval is not APPROVED`() {
        val store = InMemoryApprovalStore()
        val pending = runBlocking { store.create("party.read", null, "user-42") } // still PENDING

        assertThatThrownBy { runBlocking { store.markExecuted(pending.id) } }
            .isInstanceOf(InvalidApprovalStateException::class.java)
    }

    @Test
    fun `four-eyes enforced, approval id belonging to a DIFFERENT maker is rejected and re-pends`() {
        every { identity.roles } returns setOf("ROLE_OPERATOR")
        val store = InMemoryApprovalStore()
        wirePdpAndStore(store)

        // Approved, but for a DIFFERENT maker than the current principal (user-42) — a
        // guessed/shared approval id must not unlock someone else's request.
        val pending = runBlocking { store.create("party.read", null, "someone-else") }
        runBlocking { store.decide(pending.id, "checker-99", approve = true) }
        interceptor.httpHeaders = mockk {
            every { isResolvable } returns true
            every { get() } returns mockk { every { getRequestHeader("X-Approval-Id") } returns listOf(pending.id) }
        }

        assertThatThrownBy { interceptor.authorize(makeCtx(annotatedMethod)) }
            .isInstanceOf(WebApplicationException::class.java)
        assertThat(store.created).hasSize(2) // the mismatched attempt re-issues a fresh pending approval
    }

    @Test
    fun `four-eyes enforced, an approval granted for a DIFFERENT resource is rejected and re-pends`() {
        every { identity.roles } returns setOf("ROLE_OPERATOR")
        val store = InMemoryApprovalStore()
        wirePdpAndStore(store)

        // Approved for grantee-A, replayed against grantee-B by the SAME maker. This is the case
        // an empty `resource = ""` cannot distinguish: with no resource, every approval that maker
        // holds satisfies every call, so "approve this one decision" silently becomes "approve any
        // decision of this kind". sanctions.clear was in exactly that state until the resource
        // expression was narrowed to the specific check id.
        val pending = runBlocking { store.create("consent.grant", "grantee-A", "user-42") }
        runBlocking { store.decide(pending.id, "checker-99", approve = true) }
        interceptor.httpHeaders = mockk {
            every { isResolvable } returns true
            every { get() } returns mockk { every { getRequestHeader("X-Approval-Id") } returns listOf(pending.id) }
        }

        assertThatThrownBy {
            interceptor.authorize(makeCtx(annotatedMethodWithDottedResource, DummyRequest("grantee-B", emptyList())))
        }.isInstanceOf(WebApplicationException::class.java)
        assertThat(store.created)
            .describedAs("the mismatched resource must re-issue a fresh pending approval, not proceed")
            .hasSize(2)
    }

    @Test
    fun `four-eyes enforced, still-PENDING approval id is rejected and re-pends`() {
        every { identity.roles } returns setOf("ROLE_OPERATOR")
        val store = InMemoryApprovalStore()
        wirePdpAndStore(store)

        val pending = runBlocking { store.create("party.read", null, "user-42") } // never decided
        interceptor.httpHeaders = mockk {
            every { isResolvable } returns true
            every { get() } returns mockk { every { getRequestHeader("X-Approval-Id") } returns listOf(pending.id) }
        }

        assertThatThrownBy { interceptor.authorize(makeCtx(annotatedMethod)) }
            .isInstanceOf(WebApplicationException::class.java)
    }

    @Test
    fun `four-eyes not required proceeds regardless of enforce flag, no approval ever created`() {
        every { identity.roles } returns setOf("ROLE_OPERATOR")
        val pdp = object : PolicyDecisionPoint {
            override suspend fun allow(query: AuthzQuery): AuthzDecision = AuthzDecision(allow = true)
        }
        interceptor.pdp = mockk {
            every { isResolvable } returns true
            every { get() } returns pdp
        }
        interceptor.fourEyesEnforce = true
        val store = InMemoryApprovalStore()
        interceptor.approvalStore = mockk {
            every { isResolvable } returns true
            every { get() } returns store
        }

        val result = interceptor.authorize(makeCtx(annotatedMethod))
        assertThat(result).isEqualTo("ok")
        assertThat(store.created).isEmpty()
    }
}
