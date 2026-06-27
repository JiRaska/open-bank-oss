// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.flags

import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import jakarta.interceptor.InvocationContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class FeatureFlagInterceptorTest {

    /** Sample whose methods carry the real annotation so the interceptor reads it via reflection. */
    @Suppress("unused")
    private class Sample {
        @FeatureFlag(flag = "new-router")
        fun gated(): String = "ran"

        @FeatureFlag(flag = "split-flag", targetingKey = "#partyId")
        fun targeted(partyId: String): String = "ran"

        fun ungated(): String = "ran"
    }

    /** Captures the [EvalContext] the interceptor builds, to assert targeting-key extraction. */
    private class CapturingClient(private val on: Boolean) : FeatureClient {
        var lastContext: EvalContext? = null
        override fun boolean(flag: String, default: Boolean, ctx: EvalContext): FlagEvaluation<Boolean> {
            lastContext = ctx
            return FlagEvaluation(flag, on, reason = EvaluationReason.STATIC)
        }
        override fun string(flag: String, default: String, ctx: EvalContext) =
            FlagEvaluation(flag, default, reason = EvaluationReason.DEFAULT)
        override fun integer(flag: String, default: Long, ctx: EvalContext) =
            FlagEvaluation(flag, default, reason = EvaluationReason.DEFAULT)
        override fun double(flag: String, default: Double, ctx: EvalContext) =
            FlagEvaluation(flag, default, reason = EvaluationReason.DEFAULT)
    }

    private fun contextFor(methodName: String, vararg args: Any?): InvocationContext {
        val method = Sample::class.java.declaredMethods.first { it.name == methodName }
        val ctx = mockk<InvocationContext>()
        every { ctx.method } returns method
        every { ctx.parameters } returns arrayOf(*args)
        every { ctx.proceed() } returns "proceeded"
        return ctx
    }

    private fun interceptorWith(client: FeatureClient?, resolvable: Boolean): FeatureFlagInterceptor {
        @Suppress("UNCHECKED_CAST")
        val instance = mockk<Instance<FeatureClient>>()
        every { instance.isResolvable } returns resolvable
        if (client != null) every { instance.get() } returns client
        return FeatureFlagInterceptor().apply { flags = instance }
    }

    @Test
    fun `proceeds when the method is not annotated`() {
        val interceptor = interceptorWith(StaticFeatureClient(), resolvable = true)
        assertThat(interceptor.gate(contextFor("ungated"))).isEqualTo("proceeded")
    }

    @Test
    fun `proceeds when the flag is on`() {
        val interceptor = interceptorWith(StaticFeatureClient(mapOf("new-router" to true)), resolvable = true)
        assertThat(interceptor.gate(contextFor("gated"))).isEqualTo("proceeded")
    }

    @Test
    fun `short-circuits with FeatureDisabledException when the flag is off`() {
        val interceptor = interceptorWith(StaticFeatureClient(mapOf("new-router" to false)), resolvable = true)
        assertThatThrownBy { interceptor.gate(contextFor("gated")) }
            .isInstanceOf(FeatureDisabledException::class.java)
            .hasMessageContaining("new-router")
    }

    @Test
    fun `short-circuits when the flag is absent (default off)`() {
        val interceptor = interceptorWith(StaticFeatureClient(), resolvable = true)
        assertThatThrownBy { interceptor.gate(contextFor("gated")) }
            .isInstanceOf(FeatureDisabledException::class.java)
    }

    @Test
    fun `fails open and proceeds when no FeatureClient bean is wired`() {
        val interceptor = interceptorWith(client = null, resolvable = false)
        assertThat(interceptor.gate(contextFor("gated"))).isEqualTo("proceeded")
    }

    @Test
    fun `extracts the targeting key from the named parameter into the eval context`() {
        val client = CapturingClient(on = true)
        val interceptor = interceptorWith(client, resolvable = true)

        assertThat(interceptor.gate(contextFor("targeted", "party-99"))).isEqualTo("proceeded")
        assertThat(client.lastContext?.targetingKey).isEqualTo("party-99")
    }
}
