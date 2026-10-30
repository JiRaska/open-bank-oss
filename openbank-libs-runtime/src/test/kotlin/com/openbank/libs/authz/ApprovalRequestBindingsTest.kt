// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.authz

import com.openbank.libs.approval.ApprovalRequestBinding
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import jakarta.interceptor.InvocationContext
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.StringReader
import java.lang.reflect.Method
import java.math.BigDecimal
import java.nio.channels.Channels
import java.nio.file.Paths
import java.util.concurrent.Flow
import kotlin.coroutines.Continuation
import java.security.Principal as JavaPrincipal

/**
 * Unit tests for [ApprovalRequestBindings] one level below [AuthorizeInterceptor]: parameter
 * naming, framework-context skipping, unbindable-argument refusal, token-wise summary redaction
 * and the renderer contract. The interceptor tests cover the wiring end to end; these pin the
 * branches that wiring can never reach with a single happy-path argument — every unbindable type,
 * both isContext match routes, the compound-token length boundary, and all four renderer
 * outcomes. Each surviving mutant in this class was a branch the fleet's four-eyes evidence
 * silently depended on but no test had ever executed.
 */
class ApprovalRequestBindingsTest {

    data class Transfer(val amount: BigDecimal?, val note: String?)

    data class Nested(val password: String, val label: String)

    @Suppress("LongParameterList") // one fixture DTO carries every redaction boundary case
    data class RedactionPayload(
        val newPin: String,
        val apiKey: String,
        val password: String,
        val cvv: String,
        val shipping: String,
        val company: String,
        val spin: String,
        val mytoken: String,
        val myapikey: String,
        val apikeyholder: String,
        val nested: Nested,
        val items: List<Nested>,
    )

    @Suppress("UnusedParameter") // invoked only via InvocationContext reflection, not directly
    fun twoArgs(amount: BigDecimal?, note: String?) = Unit

    @Suppress("UnusedParameter")
    fun payload(body: Any?) = Unit

    @Suppress("UnusedParameter")
    fun noArgs() = Unit

    @Suppress("UnusedParameter")
    fun withContexts(a: Any?, b: Any?, c: Any?, d: Any?, e: Any?) = Unit

    private val twoArgsMethod: Method =
        ApprovalRequestBindingsTest::class.java.getDeclaredMethod(
            "twoArgs",
            BigDecimal::class.java,
            String::class.java,
        )
    private val payloadMethod: Method =
        ApprovalRequestBindingsTest::class.java.getDeclaredMethod("payload", Any::class.java)
    private val noArgsMethod: Method =
        ApprovalRequestBindingsTest::class.java.getDeclaredMethod("noArgs")
    private val withContextsMethod: Method =
        ApprovalRequestBindingsTest::class.java.getDeclaredMethod(
            "withContexts",
            Any::class.java,
            Any::class.java,
            Any::class.java,
            Any::class.java,
            Any::class.java,
        )

    // StringBuilder is a pure-Java class: its methods carry no Kotlin metadata, so kotlinFunction
    // is null and the binding must fall back to positional argN names.
    private val javaLengthMethod: Method = StringBuilder::class.java.getMethod("length")

    private fun makeCtx(method: Method, vararg params: Any?): InvocationContext {
        val ctx = mockk<InvocationContext>()
        every { ctx.method } returns method
        every { ctx.parameters } returns arrayOf(*params)
        return ctx
    }

    @Test
    fun `kotlin parameter names key the argument map in declaration order`() {
        val args = ApprovalRequestBindings.arguments(
            makeCtx(twoArgsMethod, BigDecimal("10.50"), "rent"),
            "payment.create",
        )
        assertThat(args.keys).containsExactly("amount", "note")
        assertThat(args["amount"] as BigDecimal).isEqualByComparingTo(BigDecimal("10.50"))
        assertThat(args["note"]).isEqualTo("rent")
    }

    @Test
    fun `null arguments are kept as explicit null entries`() {
        val args = ApprovalRequestBindings.arguments(makeCtx(twoArgsMethod, null, null), "payment.create")
        assertThat(args).containsOnlyKeys("amount", "note")
        assertThat(args["amount"]).isNull()
        assertThat(args["note"]).isNull()
    }

    @Test
    fun `java methods without kotlin metadata fall back to positional argument names`() {
        val args = ApprovalRequestBindings.arguments(makeCtx(javaLengthMethod, "abc"), "payment.create")
        assertThat(args).containsOnlyKeys("arg0")
        assertThat(args["arg0"]).isEqualTo("abc")
    }

    @Test
    fun `framework context arguments are skipped, not fingerprinted`() {
        val contextArgs = arrayOf<Any?>(
            mockk<SecurityIdentity>(),
            JavaPrincipal { "user-1" },
            mockk<Continuation<Any?>>(),
            MediaType.APPLICATION_JSON_TYPE,
            mockk<HttpHeaders>(),
        )
        val args = ApprovalRequestBindings.arguments(makeCtx(withContextsMethod, *contextArgs), "payment.create")
        assertThat(args)
            .describedAs(
                "security identities, principals, continuations and JAX-RS/Vert.x context objects are not business content",
            )
            .isEmpty()
    }

    @Test
    fun `stream file and channel arguments refuse binding with 503`() {
        val unbindables = listOf<Any>(
            ByteArrayInputStream(byteArrayOf(1, 2, 3)),
            StringReader("body"),
            File("upload.bin"),
            Paths.get("upload.bin"),
            Channels.newChannel(ByteArrayInputStream(byteArrayOf(1))),
            Flow.Publisher<Int> { subscriber -> subscriber.onComplete() },
        )
        unbindables.forEach { value ->
            assertThatThrownBy {
                ApprovalRequestBindings.of(makeCtx(payloadMethod, value), "payment.create", null)
            }
                .isInstanceOf(PolicyDecisionException::class.java)
                .hasMessageContaining("cannot be bound")
                .hasMessageContaining("'body'")
        }
    }

    @Test
    fun `credential-like fields are redacted token-wise in the checker summary`() {
        val payload = RedactionPayload(
            newPin = "s-newPin",
            apiKey = "s-apiKey",
            password = "s-password",
            cvv = "s-cvv",
            shipping = "v-shipping",
            company = "v-company",
            spin = "v-spin",
            mytoken = "v-mytoken",
            myapikey = "s-myapikey",
            apikeyholder = "v-apikeyholder",
            nested = Nested(password = "s-nested", label = "v-nested-label"),
            items = listOf(Nested(password = "s-item", label = "v-item-label")),
        )
        val binding = ApprovalRequestBindings.of(makeCtx(payloadMethod, payload), "payment.create", "res-9")
        val summary = binding.summary!!

        // redacted — the checker's view never carries these values, including inside nested
        // objects and arrays
        listOf("s-newPin", "s-apiKey", "s-password", "s-cvv", "s-myapikey", "s-nested", "s-item")
            .forEach { assertThat(summary).doesNotContain(it) }
        assertThat(summary).contains("\"***\"")

        // NOT redacted — token-wise matching must not eat innocent fields:
        //  - "shipping" contains "pin" glued inside a word
        //  - "company" contains "pan" glued inside a word
        //  - "spin" ends with "pin" but the compound rule needs a token LONGER than MIN_COMPOUND
        //  - "mytoken" ends with "token" but "token" is exactly MIN long, not longer
        //  - "apikeyholder" contains "apikey" but the compound rule is suffix-only
        listOf(
            "v-shipping",
            "v-company",
            "v-spin",
            "v-mytoken",
            "v-apikeyholder",
            "v-nested-label",
            "v-item-label",
        ).forEach { assertThat(summary).contains(it) }

        assertThat(summary)
            .contains("endpoint=ApprovalRequestBindingsTest#payload")
            .contains("resource=res-9")
    }

    @Test
    fun `the summary is control-character free and capped at the stored limit`() {
        val binding = ApprovalRequestBindings.of(
            makeCtx(twoArgsMethod, BigDecimal.ONE, "line1\nline2\t" + "x".repeat(5000)),
            "payment.create",
            null,
        )
        val summary = binding.summary!!
        assertThat(summary).doesNotContain("\n", "\t")
        assertThat(summary.length).isEqualTo(ApprovalRequestBinding.MAX_SUMMARY_LENGTH)
        assertThat(summary.endsWith("…")).isTrue()
    }

    @Test
    fun `the fingerprint binds the canonical values, not the instances or number scale`() {
        val first = ApprovalRequestBindings.of(
            makeCtx(twoArgsMethod, BigDecimal("10.50"), "rent"),
            "payment.create",
            "res-1",
        )
        val sameAmountDifferentScale = ApprovalRequestBindings.of(
            makeCtx(twoArgsMethod, BigDecimal("10.5"), "rent"),
            "payment.create",
            "res-1",
        )
        val differentAmount = ApprovalRequestBindings.of(
            makeCtx(twoArgsMethod, BigDecimal("10.51"), "rent"),
            "payment.create",
            "res-1",
        )

        // 10.50 and 10.5 are the same money — the approval binds the amount, not its serialisation
        assertThat(first.fingerprint).isEqualTo(sameAmountDifferentScale.fingerprint)
        assertThat(first.fingerprint).isNotEqualTo(differentAmount.fingerprint)
    }

    @Test
    fun `a method with no business arguments binds an empty argument set`() {
        val binding = ApprovalRequestBindings.of(makeCtx(noArgsMethod), "catalog.read", null)
        assertThat(binding.summary).contains("args={}").contains("resource=-")
    }

    @Test
    fun `a null renderer keeps the generic summary`() {
        val binding = runBlocking {
            ApprovalRequestBindings.of(
                makeCtx(twoArgsMethod, BigDecimal.ONE, "rent"),
                "payment.create",
                "res-1",
                null,
            )
        }
        assertThat(binding.summary).contains("endpoint=ApprovalRequestBindingsTest#twoArgs")
    }

    @Test
    fun `a covering renderer replaces the summary and sees the business arguments`() {
        var seen: Map<String, Any?> = emptyMap()
        val renderer = object : ApprovalSummaryRenderer {
            override suspend fun render(action: String, resourceId: String?, arguments: Map<String, Any?>): String {
                seen = arguments
                return "approve $action for $resourceId"
            }
        }
        val binding = runBlocking {
            ApprovalRequestBindings.of(
                makeCtx(twoArgsMethod, BigDecimal.ONE, "rent"),
                "payment.create",
                "res-1",
                renderer,
            )
        }
        assertThat(binding.summary).isEqualTo("approve payment.create for res-1")
        assertThat(seen).containsOnlyKeys("amount", "note")
    }

    @Test
    fun `a renderer returning null keeps the generic summary`() {
        val renderer = object : ApprovalSummaryRenderer {
            override suspend fun render(action: String, resourceId: String?, arguments: Map<String, Any?>) = null
        }
        val binding = runBlocking {
            ApprovalRequestBindings.of(
                makeCtx(twoArgsMethod, BigDecimal.ONE, "rent"),
                "payment.create",
                "res-1",
                renderer,
            )
        }
        assertThat(binding.summary).contains("endpoint=ApprovalRequestBindingsTest#twoArgs")
    }

    @Test
    fun `a throwing renderer refuses the call rather than issuing an unrenderable approval`() {
        val renderer = object : ApprovalSummaryRenderer {
            override suspend fun render(action: String, resourceId: String?, arguments: Map<String, Any?>): String =
                error("template store unreachable")
        }
        assertThatThrownBy {
            runBlocking {
                ApprovalRequestBindings.of(
                    makeCtx(twoArgsMethod, BigDecimal.ONE, "rent"),
                    "payment.create",
                    "res-1",
                    renderer,
                )
            }
        }
            .isInstanceOf(PolicyDecisionException::class.java)
            .hasMessageContaining("could not be rendered")
    }

    @Test
    fun `a renderer output is flattened and capped like the generic summary`() {
        val renderer = object : ApprovalSummaryRenderer {
            override suspend fun render(action: String, resourceId: String?, arguments: Map<String, Any?>) =
                "y\n" + "y".repeat(5000)
        }
        val binding = runBlocking {
            ApprovalRequestBindings.of(
                makeCtx(twoArgsMethod, BigDecimal.ONE, "rent"),
                "payment.create",
                null,
                renderer,
            )
        }
        assertThat(binding.summary).doesNotContain("\n")
        assertThat(binding.summary!!.length).isEqualTo(ApprovalRequestBinding.MAX_SUMMARY_LENGTH)
    }
}
