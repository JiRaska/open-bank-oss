// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.authz

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import io.vertx.core.Vertx
import jakarta.interceptor.InvocationContext
import jakarta.ws.rs.core.SecurityContext
import kotlinx.coroutines.CompletableDeferred
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.security.Principal
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AuthorizeInterceptorReactiveDispatchTest {
    @Authorize(action = "loan.disburse")
    fun uniEndpoint(): Uni<String> = Uni.createFrom().item("executed")

    @Authorize(action = "loan.disburse")
    fun stageEndpoint(): CompletionStage<String> = CompletableFuture.completedFuture("executed")

    private class DelayedPdp : PolicyDecisionPoint {
        val started = CompletableFuture<Unit>()
        val stopped = CompletableFuture<Unit>()
        val gate = CompletableDeferred<AuthzDecision>()
        override suspend fun allow(query: AuthzQuery): AuthzDecision {
            started.complete(Unit)
            try {
                return gate.await()
            } finally {
                stopped.complete(Unit)
            }
        }
    }

    @Test
    fun `Uni authorization releases the event loop and resumes the target on its original context`() {
        checkDispatch("uniEndpoint")
    }

    @Test
    fun `CompletionStage authorization releases the event loop and preserves its context`() {
        checkDispatch("stageEndpoint")
    }

    @ParameterizedTest
    @ValueSource(strings = ["uniEndpoint", "stageEndpoint"])
    fun `an asynchronous denial never invokes the target`(methodName: String) {
        val pdp = DelayedPdp()
        val ctx = context(methodName)
        val result = future(interceptor(pdp).authorize(ctx))
        pdp.started.get(2, TimeUnit.SECONDS)
        pdp.gate.complete(AuthzDecision(allow = false, reason = "denied"))

        assertThatThrownBy { result.get(2, TimeUnit.SECONDS) }.hasCauseInstanceOf(PolicyDeniedException::class.java)
        verify(exactly = 0) { ctx.proceed() }
    }

    @ParameterizedTest
    @ValueSource(strings = ["uniEndpoint", "stageEndpoint"])
    fun `an asynchronous policy outage fails closed`(methodName: String) {
        val pdp = DelayedPdp()
        val ctx = context(methodName)
        val result = future(interceptor(pdp).authorize(ctx))
        pdp.started.get(2, TimeUnit.SECONDS)
        pdp.gate.completeExceptionally(IllegalStateException("provider unavailable"))

        assertThatThrownBy { result.get(2, TimeUnit.SECONDS) }.hasCauseInstanceOf(PolicyDecisionException::class.java)
        verify(exactly = 0) { ctx.proceed() }
    }

    @ParameterizedTest
    @ValueSource(strings = ["uniEndpoint", "stageEndpoint"])
    fun `an asynchronous provider error completes the result exceptionally`(methodName: String) {
        val pdp = DelayedPdp()
        val ctx = context(methodName)
        val result = future(interceptor(pdp).authorize(ctx))
        pdp.gate.completeExceptionally(AssertionError("provider failed"))

        assertThatThrownBy { result.get(2, TimeUnit.SECONDS) }.hasCauseInstanceOf(PolicyDecisionException::class.java)
        verify(exactly = 0) { ctx.proceed() }
    }

    @ParameterizedTest
    @ValueSource(strings = ["uniEndpoint", "stageEndpoint"])
    fun `cancelling an advisory request cancels the policy check without proceeding`(methodName: String) {
        val pdp = DelayedPdp()
        val ctx = context(methodName)
        val sut = interceptor(pdp).apply { enforce = false }
        val result = future(sut.authorize(ctx))
        pdp.started.get(2, TimeUnit.SECONDS)
        result.cancel(true)
        pdp.stopped.get(2, TimeUnit.SECONDS)
        pdp.gate.complete(AuthzDecision(allow = true))

        assertThat(result).isCancelled()
        verify(exactly = 0) { ctx.proceed() }
    }

    @ParameterizedTest
    @ValueSource(strings = ["uniEndpoint", "stageEndpoint"])
    fun `an exception from the authorized target is delivered through its reactive result`(methodName: String) {
        val pdp = DelayedPdp()
        val ctx = context(methodName)
        val failure = IllegalArgumentException("target failed")
        every { ctx.proceed() } throws failure
        val result = future(interceptor(pdp).authorize(ctx))
        pdp.gate.complete(AuthzDecision(allow = true))

        assertThatThrownBy { result.get(2, TimeUnit.SECONDS) }.hasCause(failure)
        verify(exactly = 1) { ctx.proceed() }
    }

    private fun context(methodName: String): InvocationContext = mockk {
        every { method } returns this@AuthorizeInterceptorReactiveDispatchTest.javaClass.getDeclaredMethod(methodName)
        every { parameters } returns emptyArray()
    }

    private fun future(output: Any?): CompletableFuture<*> = when (output) {
        is Uni<*> -> output.subscribeAsCompletionStage()
        is CompletionStage<*> -> output.toCompletableFuture()
        else -> error("Unexpected reactive return type")
    }

    private fun checkDispatch(methodName: String) {
        val vertx = Vertx.vertx()
        val context = vertx.getOrCreateContext()
        val pdp = DelayedPdp()
        val interceptor = interceptor(pdp)
        val returned = CompletableFuture<Unit>()
        val result = CompletableFuture<Any?>()
        val invokedOnContext = AtomicBoolean()
        val ctx = mockk<InvocationContext>()
        every { ctx.method } returns javaClass.getDeclaredMethod(methodName)
        every { ctx.parameters } returns emptyArray()
        every { ctx.proceed() } answers {
            invokedOnContext.set(Vertx.currentContext() === context)
            if (methodName == "uniEndpoint") uniEndpoint() else stageEndpoint()
        }

        try {
            context.runOnContext {
                try {
                    val output = interceptor.authorize(ctx)
                    returned.complete(Unit)
                    when (output) {
                        is Uni<*> -> output.subscribe().with(
                            { result.complete(it) },
                            { result.completeExceptionally(it) },
                        )
                        is CompletionStage<*> -> output.whenComplete { value, failure ->
                            if (failure == null) result.complete(value) else result.completeExceptionally(failure)
                        }
                        else -> result.completeExceptionally(AssertionError("Unexpected reactive return type"))
                    }
                } catch (failure: Exception) {
                    returned.completeExceptionally(failure)
                    result.completeExceptionally(failure)
                }
            }
            // The old runBlocking bridge cannot return until the PDP answers. The timeout is
            // bounded, and finally releases its gate so a regression fails instead of hanging.
            returned.get(2, TimeUnit.SECONDS)
            pdp.started.get(2, TimeUnit.SECONDS)
            assertThat(invokedOnContext).isFalse()
            // Completing from the test thread must restore the original context before proceeding.
            pdp.gate.complete(AuthzDecision(allow = true))
            assertThat(result.get(2, TimeUnit.SECONDS)).isEqualTo("executed")
            assertThat(invokedOnContext).isTrue()
        } finally {
            pdp.gate.complete(AuthzDecision(allow = false))
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
        }
    }

    private fun interceptor(pdp: PolicyDecisionPoint): AuthorizeInterceptor {
        val principal = Principal { "test-maker" }
        val sc = mockk<SecurityContext> { every { userPrincipal } returns principal }
        val caller = mockk<SecurityIdentity> {
            every { roles } returns emptySet()
            every { this@mockk.principal } returns principal
        }
        return AuthorizeInterceptor().apply {
            this.pdp = mockk {
                every { isResolvable } returns true
                every { get() } returns pdp
            }
            securityContext = mockk { every { get() } returns sc }
            identity = mockk { every { get() } returns caller }
            httpHeaders = mockk { every { isResolvable } returns false }
            metrics = mockk { every { isResolvable } returns false }
            securityTelemetry = mockk { every { isResolvable } returns false }
            approvalStore = mockk { every { isResolvable } returns false }
            clock = Clock.systemUTC()
        }
    }
}
