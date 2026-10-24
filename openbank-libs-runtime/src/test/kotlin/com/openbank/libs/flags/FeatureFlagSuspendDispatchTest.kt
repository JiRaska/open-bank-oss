// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.flags

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import jakarta.interceptor.InvocationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

class FeatureFlagSuspendDispatchTest {
    // A real annotated suspend method is needed for the interceptor's JVM signature dispatch.
    @Suppress("FunctionOnlyReturningConstant")
    @FeatureFlag(flag = "test-flag")
    suspend fun endpoint(): String = "executed"

    @FeatureFlag(flag = "test-flag")
    fun uniEndpoint(): Uni<String> = Uni.createFrom().item("executed")

    @Test
    fun `a slow synchronous flag provider does not park a suspend endpoint's caller`() {
        val releaseProvider = CountDownLatch(1)
        val enteredProvider = CountDownLatch(1)
        val client = object : FeatureClient by DefaultsFeatureClient() {
            override fun enabled(flag: String, ctx: EvalContext): Boolean {
                enteredProvider.countDown()
                check(releaseProvider.await(5, TimeUnit.SECONDS))
                return true
            }
        }
        val interceptor = FeatureFlagInterceptor().apply {
            flags = mockk {
                every { isResolvable } returns true
                every { get() } returns client
            }
        }
        val completed = CompletableFuture<Any?>()
        val caller = object : Continuation<Any?> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Any?>) {
                result.fold(completed::complete, completed::completeExceptionally)
            }
        }
        val ctx = mockk<InvocationContext>()
        var arguments = arrayOf<Any?>(caller)
        every { ctx.method } returns javaClass.getDeclaredMethod("endpoint", Continuation::class.java)
        every { ctx.parameters } answers { arguments }
        every { ctx.parameters = any() } answers { arguments = firstArg() }
        every { ctx.proceed() } returns "executed"
        val executor = Executors.newSingleThreadExecutor()
        try {
            val returned = executor.submit<Any?> { interceptor.gate(ctx) }
            assertThat(returned.get(2, TimeUnit.SECONDS)).isSameAs(COROUTINE_SUSPENDED)
            assertThat(enteredProvider.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(completed).isNotDone()
            releaseProvider.countDown()
            assertThat(completed.get(2, TimeUnit.SECONDS)).isEqualTo("executed")
        } finally {
            releaseProvider.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `an off flag fails the suspend call without invoking its target`() {
        val releaseProvider = CountDownLatch(1)
        val completed = CompletableFuture<Any?>()
        val ctx = suspendInvocation(completed)
        val gate = interceptor {
            check(releaseProvider.await(5, TimeUnit.SECONDS))
            false
        }

        try {
            assertThat(gate.gate(ctx)).isSameAs(COROUTINE_SUSPENDED)
            releaseProvider.countDown()
            assertThatThrownBy { completed.get(2, TimeUnit.SECONDS) }
                .hasCauseInstanceOf(FeatureDisabledException::class.java)
            verify(exactly = 0) { ctx.proceed() }
        } finally {
            releaseProvider.countDown()
        }
    }

    @Test
    fun `cancelling a suspend call interrupts its flag provider and never invokes the target`() {
        val enteredProvider = CountDownLatch(1)
        val releaseProvider = CountDownLatch(1)
        val interruptedProvider = CountDownLatch(1)
        val job = Job()
        val completed = CompletableFuture<Any?>()
        val ctx = suspendInvocation(completed, job)
        val gate = interceptor {
            enteredProvider.countDown()
            try {
                check(releaseProvider.await(5, TimeUnit.SECONDS))
                true
            } catch (failure: InterruptedException) {
                interruptedProvider.countDown()
                throw failure
            }
        }

        try {
            assertThat(gate.gate(ctx)).isSameAs(COROUTINE_SUSPENDED)
            assertThat(enteredProvider.await(2, TimeUnit.SECONDS)).isTrue()
            job.cancel()

            assertThat(interruptedProvider.await(2, TimeUnit.SECONDS)).isTrue()
            assertThatThrownBy { completed.get(2, TimeUnit.SECONDS) }
                .isInstanceOf(CancellationException::class.java)
            verify(exactly = 0) { ctx.proceed() }
        } finally {
            job.cancel()
            releaseProvider.countDown()
        }
    }

    @Test
    fun `an Error from a flag provider completes the Uni exceptionally without invoking its target`() {
        val failure = AssertionError("flag provider failed")
        val gate = interceptor { throw failure }
        val ctx = uniInvocation()

        val result = (gate.gate(ctx) as Uni<*>).subscribeAsCompletionStage().toCompletableFuture()

        assertThatThrownBy { result.get(2, TimeUnit.SECONDS) }.hasCause(failure)
        verify(exactly = 0) { ctx.proceed() }
    }

    @Test
    fun `a provider outage does not poison a later successful evaluation`() {
        val evaluations = AtomicInteger()
        val outage = IOException("flag provider unavailable")
        val gate = interceptor {
            if (evaluations.incrementAndGet() == 1) throw outage
            true
        }
        val failedCall = uniInvocation()
        val recoveredCall = uniInvocation()

        val failed = (gate.gate(failedCall) as Uni<*>).subscribeAsCompletionStage().toCompletableFuture()
        assertThatThrownBy { failed.get(2, TimeUnit.SECONDS) }.hasCause(outage)
        verify(exactly = 0) { failedCall.proceed() }

        val recovered = (gate.gate(recoveredCall) as Uni<*>).subscribeAsCompletionStage().toCompletableFuture()
        assertThat(recovered.get(2, TimeUnit.SECONDS)).isEqualTo("executed")
        assertThat(evaluations.get()).isEqualTo(2)
        verify(exactly = 1) { recoveredCall.proceed() }
    }

    @Test
    fun `cancelling one flag evaluation does not poison a later independent call`() {
        val evaluations = AtomicInteger()
        val enteredProvider = CountDownLatch(1)
        val releaseProvider = CountDownLatch(1)
        val interruptedProvider = CountDownLatch(1)
        val gate = interceptor {
            if (evaluations.incrementAndGet() == 1) {
                enteredProvider.countDown()
                try {
                    check(releaseProvider.await(5, TimeUnit.SECONDS))
                } catch (failure: InterruptedException) {
                    interruptedProvider.countDown()
                    throw failure
                }
            }
            true
        }
        val cancelledCall = uniInvocation()
        val recoveredCall = uniInvocation()
        val cancelled = (gate.gate(cancelledCall) as Uni<*>).subscribeAsCompletionStage().toCompletableFuture()

        try {
            assertThat(enteredProvider.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(cancelled.cancel(true)).isTrue()
            assertThat(interruptedProvider.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(cancelled).isCancelled()
            verify(exactly = 0) { cancelledCall.proceed() }

            val recovered = (gate.gate(recoveredCall) as Uni<*>).subscribeAsCompletionStage().toCompletableFuture()
            assertThat(recovered.get(2, TimeUnit.SECONDS)).isEqualTo("executed")
            assertThat(evaluations.get()).isEqualTo(2)
            verify(exactly = 1) { recoveredCall.proceed() }
            verify(exactly = 0) { cancelledCall.proceed() }
        } finally {
            cancelled.cancel(true)
            releaseProvider.countDown()
        }
    }

    private fun uniInvocation(): InvocationContext {
        val ctx = mockk<InvocationContext>()
        every { ctx.method } returns javaClass.getDeclaredMethod("uniEndpoint")
        every { ctx.parameters } returns emptyArray()
        every { ctx.proceed() } answers { uniEndpoint() }
        return ctx
    }

    private fun interceptor(evaluate: () -> Boolean): FeatureFlagInterceptor {
        val client = object : FeatureClient by DefaultsFeatureClient() {
            override fun enabled(flag: String, ctx: EvalContext): Boolean = evaluate()
        }
        return FeatureFlagInterceptor().apply {
            flags = mockk {
                every { isResolvable } returns true
                every { get() } returns client
            }
        }
    }

    private fun suspendInvocation(
        completed: CompletableFuture<Any?>,
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
    ): InvocationContext {
        val caller = object : Continuation<Any?> {
            override val context = coroutineContext
            override fun resumeWith(result: Result<Any?>) {
                result.fold(completed::complete, completed::completeExceptionally)
            }
        }
        val ctx = mockk<InvocationContext>()
        var arguments = arrayOf<Any?>(caller)
        every { ctx.method } returns javaClass.getDeclaredMethod("endpoint", Continuation::class.java)
        every { ctx.parameters } answers { arguments }
        every { ctx.parameters = any() } answers { arguments = firstArg() }
        every { ctx.proceed() } returns "executed"
        return ctx
    }
}
