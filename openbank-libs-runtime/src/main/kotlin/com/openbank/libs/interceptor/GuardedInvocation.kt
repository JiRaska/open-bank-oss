// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.interceptor

import io.smallrye.mutiny.Uni
import io.vertx.core.Context
import io.vertx.core.Vertx
import jakarta.interceptor.InvocationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletionStage
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Runs a suspending guard before the intercepted method, preserving its return shape.
 *
 * Suspend calls inherit the caller's coroutine. Uni calls authorize per subscription;
 * CompletionStage calls start immediately. Both reactive paths resume on the captured Vert.x
 * context, including its request-local state. Cancelling a subscription cancels its pending guard.
 * Only ordinary synchronous methods use a blocking bridge and must run on worker threads.
 */
internal object GuardedInvocation {
    fun proceed(ctx: InvocationContext, guard: suspend () -> Unit): Any? {
        val continuationIndex = ctx.parameters.indexOfLast { it is Continuation<*> }
        if (continuationIndex >= 0) return proceedSuspending(ctx, continuationIndex, guard)

        val returnType = ctx.method.returnType
        if (Uni::class.java.isAssignableFrom(returnType)) {
            return guardedUni(guard).chain { _ -> ctx.proceed() as Uni<*> }
        }
        if (CompletionStage::class.java.isAssignableFrom(returnType)) {
            return guardedUni(guard).chain { _ ->
                Uni.createFrom().completionStage(ctx.proceed() as CompletionStage<*>)
            }.subscribeAsCompletionStage()
        }
        runBlocking { guard() }
        return ctx.proceed()
    }

    private fun proceedSuspending(ctx: InvocationContext, continuationIndex: Int, guard: suspend () -> Unit): Any? {
        // The JVM erases a suspend method's result; the replaced continuation carries that result.
        @Suppress("UNCHECKED_CAST")
        val caller = ctx.parameters[continuationIndex] as Continuation<Any?>
        val pipeline: suspend () -> Any? = {
            guard()
            suspendCoroutineUninterceptedOrReturn { own ->
                val params = ctx.parameters
                params[continuationIndex] = own
                ctx.parameters = params
                ctx.proceed()
            }
        }
        return pipeline.startCoroutineUninterceptedOrReturn(caller)
    }

    // This is an asynchronous result boundary: even Error must terminate the subscriber's result.
    @Suppress("TooGenericExceptionCaught")
    private fun guardedUni(guard: suspend () -> Unit): Uni<Unit> {
        val dispatcher = Vertx.currentContext()?.let(::ContextDispatcher) ?: Dispatchers.Unconfined
        return Uni.createFrom().emitter { emitter ->
            val job = Job()
            emitter.onTermination { job.cancel() }
            CoroutineScope(dispatcher + job).launch {
                try {
                    guard()
                    ensureActive()
                    emitter.complete(Unit)
                } catch (failure: Throwable) {
                    emitter.fail(failure)
                }
            }
        }
    }

    private class ContextDispatcher(private val vertxContext: Context) : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = Vertx.currentContext() !== vertxContext

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            vertxContext.runOnContext { block.run() }
        }
    }
}
