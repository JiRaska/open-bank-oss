// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.authz

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.quarkus.security.identity.SecurityIdentity
import jakarta.interceptor.InvocationContext
import jakarta.ws.rs.core.SecurityContext
import kotlinx.coroutines.CompletableDeferred
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import java.security.Principal as JavaPrincipal

/**
 * The interceptor must not BLOCK the thread it is called on when the endpoint is a suspend
 * function (#9874).
 *
 * Why this is a unit test and not only the integration one: the deadlock is only observable on a
 * machine with few CPUs, because Vert.x sizes its event-loop pool per CPU — measured, the
 * transaction-service IT passes on a developer laptop and fails at `-XX:ActiveProcessorCount=2`
 * with all four of its cases timing out and the Gradle task dying at its 25-minute bound. A test
 * that can only fail on a 2-CPU runner is a test this repo cannot rely on, so this asserts the
 * MECHANISM instead: given a policy decision that has not answered yet, `authorize` must hand back
 * `COROUTINE_SUSPENDED` rather than sit on the thread waiting.
 *
 * The bound matters. If someone reintroduces `runBlocking`, this test must FAIL rather than hang:
 * the call runs on its own executor and the assertion is on a 2-second `get`, so a blocking bridge
 * is reported as a timeout, not as a stuck suite.
 */
class AuthorizeInterceptorSuspendDispatchTest {

    @Suppress("UnusedParameter")
    @Authorize(action = "balance.credit")
    suspend fun suspendEndpoint(amount: Long): String = "executed-$amount"

    private val suspendMethod: Method =
        AuthorizeInterceptorSuspendDispatchTest::class.java.getDeclaredMethod(
            "suspendEndpoint",
            Long::class.java,
            Continuation::class.java,
        )

    /** A PDP that never answers during the test — it stands in for a store/PDP round trip in flight. */
    private class NeverAnsweringPdp : PolicyDecisionPoint {
        val gate = CompletableDeferred<AuthzDecision>()
        override suspend fun allow(query: AuthzQuery): AuthzDecision = gate.await()
    }

    private fun interceptor(pdp: PolicyDecisionPoint): AuthorizeInterceptor {
        val sc = mockk<SecurityContext>()
        every { sc.userPrincipal } returns JavaPrincipal { "maker-1" }
        val securityIdentity = mockk<SecurityIdentity>()
        every { securityIdentity.roles } returns emptySet()
        return AuthorizeInterceptor().apply {
            this.pdp = mockk {
                every { isResolvable } returns true
                every { get() } returns pdp
            }
            securityContext = mockk { every { get() } returns sc }
            identity = mockk { every { get() } returns securityIdentity }
            httpHeaders = mockk { every { isResolvable } returns false }
            metrics = mockk { every { isResolvable } returns false }
            securityTelemetry = mockk { every { isResolvable } returns false }
            approvalStore = mockk { every { isResolvable } returns false }
            enforce = true
            fourEyesEnforce = false
            clock = Clock.fixed(Instant.parse("2026-09-13T07:00:00Z"), ZoneOffset.UTC)
        }
    }

    private fun suspendCtx(): Pair<InvocationContext, Continuation<Any?>> {
        val caller = object : Continuation<Any?> {
            override val context: CoroutineContext = EmptyCoroutineContext
            override fun resumeWith(result: Result<Any?>) = Unit
        }
        val params = arrayOf<Any?>(1L, caller)
        val ctx = mockk<InvocationContext>()
        every { ctx.method } returns suspendMethod
        every { ctx.parameters } returns params
        every { ctx.parameters = any() } just runs
        every { ctx.proceed() } returns COROUTINE_SUSPENDED
        return ctx to caller
    }

    @Test
    fun `a suspend endpoint suspends instead of blocking the calling thread`() {
        val pdp = NeverAnsweringPdp()
        val sut = interceptor(pdp)
        val (ctx, _) = suspendCtx()

        val pool = Executors.newSingleThreadExecutor()
        try {
            val call = pool.submit<Any?> { sut.authorize(ctx) }
            // A blocking bridge parks HERE and the get() below times out — which is the point:
            // the regression is reported as a failure, never as a hung suite.
            val result = call.get(2, TimeUnit.SECONDS)
            assertThat(result)
                .describedAs(
                    "authorize must hand back COROUTINE_SUSPENDED while the decision is in flight; " +
                        "a returned value or a timeout means the thread was blocked (#9874)",
                )
                .isSameAs(COROUTINE_SUSPENDED)
        } finally {
            pdp.gate.cancel()
            pool.shutdownNow()
        }
    }

    @Test
    fun `the interceptor hands the target a continuation of its own, not the caller's`() {
        // The hop that makes the pipeline suspending: the target must resume the interceptor's
        // coroutine, which then resumes the caller. Asserting the substitution directly, because a
        // version that forwards the caller's continuation unchanged would double-resume it.
        val pdp = NeverAnsweringPdp()
        val sut = interceptor(pdp)
        val (ctx, caller) = suspendCtx()
        val handed = mutableListOf<Any?>()
        every { ctx.proceed() } answers {
            handed += ctx.parameters.last()
            COROUTINE_SUSPENDED
        }

        val pool = Executors.newSingleThreadExecutor()
        try {
            pool.submit<Any?> { sut.authorize(ctx) }
            pdp.gate.complete(AuthzDecision(allow = true, reason = "test"))
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.SECONDS)
            assertThat(handed).hasSize(1)
            assertThat(handed.single())
                .describedAs("the target must be handed the interceptor's continuation, not the endpoint's")
                .isNotSameAs(caller)
                .isInstanceOf(Continuation::class.java)
        } finally {
            pool.shutdownNow()
        }
    }
}
