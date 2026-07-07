// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.authz

import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.InvalidApprovalStateException
import com.openbank.libs.approval.PendingApproval
import com.openbank.libs.approval.SelfApprovalNotAllowedException
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import jakarta.interceptor.InvocationContext
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.ServiceUnavailableException
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
import java.time.OffsetDateTime
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

    // ---------------------------------------------------------------------------------------
    // Four-eyes gate (ADR-0155, issue #395). fourEyesPdp() simulates an OPA decision that
    // already carries attributes.four_eyes_required=true — the rego-side matching itself is
    // covered by rest_test.rego, this only exercises the interceptor's handling of the flag.
    // ---------------------------------------------------------------------------------------

    private fun fourEyesPdp(): PolicyDecisionPoint = object : PolicyDecisionPoint {
        override suspend fun allow(query: AuthzQuery): AuthzDecision =
            AuthzDecision(allow = true, reason = "ok", attributes = mapOf("four_eyes_required" to true))
    }

    /** In-memory ApprovalStore test double — behavior-equivalent to RedisApprovalStore. */
    private class FakeApprovalStore : ApprovalStore {
        val created = mutableListOf<PendingApproval>()
        private val approvals = mutableMapOf<String, PendingApproval>()
        private var nextId = 0

        override suspend fun create(
            action: String,
            resourceId: String?,
            makerId: String,
            ttlSeconds: Long,
        ): PendingApproval {
            val approval = PendingApproval(
                id = "approval-${nextId++}",
                action = action,
                resourceId = resourceId,
                makerId = makerId,
                status = ApprovalStatus.PENDING,
                createdAt = OffsetDateTime.parse("2026-06-22T10:20:00Z"),
            )
            created += approval
            approvals[approval.id] = approval
            return approval
        }

        override suspend fun find(id: String): PendingApproval? = approvals[id]

        override suspend fun decide(id: String, decidedBy: String, approve: Boolean): PendingApproval? {
            val approval = approvals[id] ?: return null
            if (decidedBy == approval.makerId) throw SelfApprovalNotAllowedException(approval.makerId)
            if (approval.status != ApprovalStatus.PENDING) {
                throw InvalidApprovalStateException(id, ApprovalStatus.PENDING, approval.status)
            }
            val decided = approval.copy(
                status = if (approve) ApprovalStatus.APPROVED else ApprovalStatus.REJECTED,
                decidedBy = decidedBy,
            )
            approvals[id] = decided
            return decided
        }

        override suspend fun markExecuted(id: String): PendingApproval? {
            val approval = approvals[id] ?: return null
            if (approval.status != ApprovalStatus.APPROVED) {
                throw InvalidApprovalStateException(id, ApprovalStatus.APPROVED, approval.status)
            }
            val executed = approval.copy(status = ApprovalStatus.EXECUTED)
            approvals[id] = executed
            return executed
        }
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
        wirePdpAndStore(FakeApprovalStore(), enforce = false)
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
        val store = FakeApprovalStore()
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
        val store = FakeApprovalStore()
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
        val store = FakeApprovalStore()
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
        val store = FakeApprovalStore()
        val pending = runBlocking { store.create("party.read", null, "user-42") } // still PENDING

        assertThatThrownBy { runBlocking { store.markExecuted(pending.id) } }
            .isInstanceOf(InvalidApprovalStateException::class.java)
    }

    @Test
    fun `four-eyes enforced, approval id belonging to a DIFFERENT maker is rejected and re-pends`() {
        every { identity.roles } returns setOf("ROLE_OPERATOR")
        val store = FakeApprovalStore()
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
    fun `four-eyes enforced, still-PENDING approval id is rejected and re-pends`() {
        every { identity.roles } returns setOf("ROLE_OPERATOR")
        val store = FakeApprovalStore()
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
        val store = FakeApprovalStore()
        interceptor.approvalStore = mockk {
            every { isResolvable } returns true
            every { get() } returns store
        }

        val result = interceptor.authorize(makeCtx(annotatedMethod))
        assertThat(result).isEqualTo("ok")
        assertThat(store.created).isEmpty()
    }
}
