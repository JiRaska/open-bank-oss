// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

/**
 * Security-native telemetry for every service (ADR-0279 WS2): authorization decisions and
 * honeytoken hits as **metrics**, and the same facts as **span attributes** on the current
 * trace — so a denied request can be followed from the alert to the exact trace that produced
 * it, without shipping PII (attribute keys carry identifiers and decisions, never payloads;
 * the same bound as ADR-0274 RUM).
 *
 * ## Why a lib primitive and not per-service counters
 *
 * The security-observability gap this closes is that the observability stack (Tempo/Loki)
 * had no security signal to correlate. If each service invented its own metric name for
 * "authz denied", the Loki/Prometheus rule pack would watch series nothing emits — the exact
 * failure mode (#5733) that metric-name constants in libs exist to prevent. Alert rules name
 * [AUTHZ_DECISIONS] / [HONEYTOKEN_HITS]; only this class may emit them.
 *
 * ## Safety
 *
 * Safe to load in services without `quarkus-micrometer` (registry is an [Instance] guard —
 * silent no-op, the [com.openbank.libs.observability.DomainMetrics] precedent) and without
 * `quarkus-opentelemetry` (span writes go through [OtelSpanAttributeWriter], reflection, the
 * [com.openbank.libs.llm.OtelTraceIdProvider] precedent — an absent OTel is one more no-op,
 * never a `NoClassDefFoundError` on the request path).
 */
@ApplicationScoped
class SecurityTelemetry {

    @Inject
    lateinit var registryInstance: Instance<MeterRegistry>

    private fun reg(): MeterRegistry? = if (registryInstance.isResolvable) registryInstance.get() else null

    /** Authorization outcome. The tag value is the enum name lowercased — low cardinality by construction. */
    enum class AuthzDecision {
        ALLOW,
        DENY,
        ;

        val tag: String get() = name.lowercase()
    }

    /**
     * Record an authorization decision: increments [AUTHZ_DECISIONS] tagged
     * `decision=allow|deny, reason=<reason>` and stamps the current span with
     * [ATTR_AUTHZ_DECISION] / [ATTR_AUTHZ_REASON].
     *
     * [reason] MUST be a low-cardinality code (e.g. `"role-missing"`, `"delegation-expired"`),
     * never a party id, token, or message — the cardinality contract of
     * [com.openbank.libs.observability.DomainMetrics] applies here unchanged.
     */
    fun recordAuthorizationDecision(decision: AuthzDecision, reason: String) {
        reg()?.let {
            Counter.builder(AUTHZ_DECISIONS)
                .description("Authorization decisions by outcome and reason code")
                .tags("decision", decision.tag, "reason", reason)
                .register(it)
                .increment()
        }
        OtelSpanAttributeWriter.set(ATTR_AUTHZ_DECISION, decision.tag)
        OtelSpanAttributeWriter.set(ATTR_AUTHZ_REASON, reason)
    }

    /**
     * Record a delegation chain depth on the current span (no metric — depth is a trace
     * concern; a gauge of it would be noise). Used by delegation-aware services so a trace
     * shows how many hops the acting identity traversed.
     */
    fun recordDelegationDepth(depth: Int) {
        OtelSpanAttributeWriter.setLong(ATTR_DELEGATION_DEPTH, depth.toLong())
    }

    companion object {
        /** Counter name for authorization decisions. Alert rules reference this constant's value. */
        const val AUTHZ_DECISIONS = "openbank.security.authz.decisions"

        /** Counter name for honeytoken hits. Alert rules reference this constant's value. */
        const val HONEYTOKEN_HITS = "openbank.security.honeytoken.hits"

        /** Span attribute key carrying the authz decision (`allow`/`deny`). */
        const val ATTR_AUTHZ_DECISION = "openbank.authz.decision"

        /** Span attribute key carrying the low-cardinality authz reason code. */
        const val ATTR_AUTHZ_REASON = "openbank.authz.reason"

        /** Span attribute key carrying the delegation chain depth. */
        const val ATTR_DELEGATION_DEPTH = "openbank.delegation.depth"
    }
}

/**
 * Writes an attribute to the ambient OpenTelemetry span, by reflection, returning silently
 * wherever OTel is not there to ask. See [com.openbank.libs.llm.OtelTraceIdProvider] for why
 * reflection and not a compile-time reference: several services do not carry
 * `quarkus-opentelemetry`, and a security signal must never be able to fail a request.
 */
internal object OtelSpanAttributeWriter {

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    fun set(key: String, value: String) {
        try {
            val spanClass = Class.forName("io.opentelemetry.api.trace.Span")
            val span = spanClass.getMethod("current").invoke(null)
            spanClass.getMethod("setAttribute", String::class.java, String::class.java)
                .invoke(span, key, value)
        } catch (ex: Exception) {
            // OTel absent or API drift — the attribute is best-effort by construction.
        } catch (ex: LinkageError) {
            // NoClassDefFoundError is an Error, not an Exception (#3376).
        }
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    fun setLong(key: String, value: Long) {
        try {
            val spanClass = Class.forName("io.opentelemetry.api.trace.Span")
            val span = spanClass.getMethod("current").invoke(null)
            spanClass.getMethod("setAttribute", String::class.java, Long::class.javaPrimitiveType)
                .invoke(span, key, value)
        } catch (ex: Exception) {
            // as above
        } catch (ex: LinkageError) {
            // as above
        }
    }
}
