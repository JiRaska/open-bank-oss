// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.libs.llm

/**
 * Reads the ambient OpenTelemetry trace id, and returns null wherever OTel is not there to ask.
 *
 * Reflection, deliberately, rather than a compile-time dependency on `io.opentelemetry.api`. Nine
 * services construct [OpenAiCompatibleLlmGatewayClient] and only some of them apply
 * `quarkus-opentelemetry`; a direct reference would resolve against a `compileOnly` artifact here
 * and then throw `NoClassDefFoundError` at runtime in the ones that do not carry it — on the LLM
 * call path, turning a missing correlation id into a failed model call. The reflective read cannot
 * do that: an absent class is one more null.
 *
 * It also keeps this module's `compileOnly` block from growing another version literal that must be
 * re-pinned by hand on every Quarkus platform bump (see this module's `build.gradle.kts` for what
 * that drift has already cost).
 *
 * Cost of the reflection is a handful of `Method.invoke`s against a network call — unmeasurable.
 */
object OtelTraceIdProvider : TraceIdProvider {

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    override fun currentTraceId(): String? = try {
        val spanClass = Class.forName("io.opentelemetry.api.trace.Span")
        val span = spanClass.getMethod("current").invoke(null)
        val spanContext = spanClass.getMethod("getSpanContext").invoke(span)
        val traceId = spanContext.javaClass.getMethod("getTraceId").invoke(spanContext) as? String
        // Validated here, not just at the call site: an INVALID span context yields the
        // all-zero id, which is a string this method must not hand out as an identifier.
        traceId?.takeIf { isValidTraceId(it) }
    } catch (ex: Exception) {
        // OTel absent, shaded, or an API change. Correlation is best-effort by construction and
        // must never be able to fail a model call, so every failure is the same answer: none.
        null
    } catch (ex: LinkageError) {
        // NoClassDefFoundError / IncompatibleClassChangeError are Errors, not Exceptions — the
        // exact gap that made an ONNX field initializer fail every endpoint of its bean (#3376).
        null
    }
}
