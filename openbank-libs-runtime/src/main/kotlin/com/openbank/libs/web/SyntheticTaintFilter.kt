// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.web

import com.openbank.libs.synthetic.SyntheticTaint
import io.opentelemetry.api.baggage.Baggage
import io.opentelemetry.context.Context
import io.opentelemetry.context.Scope
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import org.jboss.logging.MDC
import java.util.Optional

/** Request property key carrying the taint decision to anything downstream in the same request. */
const val SYNTHETIC_TAINT_PROPERTY: String = "openbank.synthetic"

/** MDC key, so every log line of a canary request is attributable without guessing. */
const val MDC_SYNTHETIC: String = "synthetic"

/** Request-scoped OTel context handle; closed by [SyntheticTaintResponseFilter]. */
private const val SYNTHETIC_TAINT_BAGGAGE_SCOPE_PROPERTY: String = "openbank.synthetic.baggage-scope"

/**
 * Where the synthetic taint ENTERS the platform (ADR-0252 phase 1, issue #4348).
 *
 * A canary's request arrives carrying [SyntheticTaint.KAFKA_HEADER]; this filter decides whether
 * to believe it, and publishes the answer for the rest of the request.
 *
 * ## The header is a claim, not a fact — and believing it blindly is a hole
 *
 * The taint's whole purpose is to exclude activity from regulatory aggregates and the AML
 * baseline. So a header that any caller could set would let anyone mark their own payments as
 * synthetic and drop themselves out of AML scoring and the regulatory returns. That is a
 * self-service evasion primitive, and it would be introduced by the very mechanism built to
 * make the platform more honest.
 *
 * Hence: the header is honoured **only** from a principal named in
 * `openbank.synthetic.trusted-principals`, and that list is **empty by default**. Shipping this
 * filter therefore changes nothing anywhere until an operator names the canary principals in one
 * environment. An anonymous request can never taint, no matter what it sends.
 *
 * ## Fail-to-real, everywhere
 *
 * Absent header, unparseable value, unauthenticated caller, untrusted principal, empty
 * configuration — all mean REAL. The asymmetry is argued in [SyntheticTaint]: real-read-as-
 * synthetic silently removes real customer money from a regulatory return, which is unbounded
 * and invisible; synthetic-read-as-real is visible and bounded.
 *
 * ## An untrusted attempt is a security signal, not noise
 *
 * A caller that sends the header without being trusted gets ignored *and* logged at WARN with
 * the principal name. Nobody sends that header by accident, so a single occurrence in production
 * is either a misconfigured canary or someone probing for exactly the hole described above.
 *
 * ## Blast radius
 *
 * `@Provider` means this runs on every request of every service that depends on libs-runtime, so
 * it deliberately injects nothing but configuration and reads only the JAX-RS
 * `SecurityContext` — no `SecurityIdentity`, no registry, nothing that can be unsatisfied at
 * build time or throw at request time.
 */
@Provider
class SyntheticTaintRequestFilter : ContainerRequestFilter {

    /**
     * Comma-separated principal names allowed to assert the taint — the canary service accounts,
     * nothing else. `Optional<String>` rather than a bare `String`: an unset property throws
     * `SRCFG00040` at boot for the latter, and this filter is on every service's request path.
     *
     * Empty by default, which means no caller can taint anything until someone deliberately
     * names one. A default that trusted anybody would be the hole this filter exists to close.
     */
    @ConfigProperty(name = "openbank.synthetic.trusted-principals")
    lateinit var trustedPrincipals: Optional<String>

    private val log = Logger.getLogger(SyntheticTaintRequestFilter::class.java)

    override fun filter(ctx: ContainerRequestContext) {
        val claimed = SyntheticTaint.isTainted(ctx.getHeaderString(SyntheticTaint.KAFKA_HEADER))
        if (!claimed) {
            ctx.setProperty(SYNTHETIC_TAINT_PROPERTY, false)
            return
        }
        val principal = ctx.securityContext?.userPrincipal?.name
        val trusted = principal != null && principal in trustedNames()
        if (!trusted) {
            // Never raise a 4xx: the request itself is legitimate, only its claim is not. Rejecting
            // it would turn a monitoring nicety into an availability risk on every service's
            // request path, which is a bad trade for a claim we can simply decline to believe.
            log.warnf(
                "synthetic taint REFUSED: principal=%s is not in openbank.synthetic.trusted-principals; " +
                    "treating the request as real. Nobody sends this header by accident.",
                principal ?: "<anonymous>",
            )
            ctx.setProperty(SYNTHETIC_TAINT_PROPERTY, false)
            return
        }
        ctx.setProperty(SYNTHETIC_TAINT_PROPERTY, true)
        MDC.put(MDC_SYNTHETIC, "true")
        // This is the observability rail, not an authorization input. Only the trusted decision
        // above may set it; accepting a browser-provided baggage value would recreate the same
        // regulatory-evasion hole as trusting the HTTP header directly.
        val baggage = Baggage.current().toBuilder()
            .put(SyntheticTaint.BAGGAGE_KEY, SyntheticTaint.headerValue())
            .build()
        ctx.setProperty(
            SYNTHETIC_TAINT_BAGGAGE_SCOPE_PROPERTY,
            baggage.storeInContext(Context.current()).makeCurrent(),
        )
    }

    // `isInitialized` rather than a Kotlin default on the field. A Kotlin default on a
    // @ConfigProperty generates a synthetic constructor that ArC builds the bean through, and the
    // annotation is then never consulted — the field keeps its fallback whatever the environment
    // says, with no error anywhere (the lending intake case, #4348's sibling gate
    // check-configproperty-kotlin-defaults.py). For THIS field that failure is silent in the worst
    // direction: the trusted list would be permanently empty, the taint could never be honoured,
    // and the canaries would look like they were simply not configured yet.
    //
    // Uninitialized therefore means "trust nobody", which is the same answer as an empty list —
    // fail-to-real, consistent with everything else here.
    private fun trustedNames(): Set<String> =
        (if (::trustedPrincipals.isInitialized) trustedPrincipals.orElse("") else "")
            .split(",")
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
}

/**
 * Clears the MDC key the request filter set. Symmetric with [CorrelationIdResponseFilter], and
 * necessary for the same reason: worker threads are pooled, so an MDC entry left behind marks
 * the NEXT request on that thread as synthetic — which would put a real customer's log lines,
 * and anything keyed off them, on the wrong side of the taint.
 */
@Provider
class SyntheticTaintResponseFilter : ContainerResponseFilter {
    override fun filter(req: ContainerRequestContext, resp: ContainerResponseContext) {
        // The scope is created only after a trusted assertion. Closing it is as important as the
        // MDC cleanup: OTel Context is thread-local, and a leak would mark the next request's
        // trace as synthetic. A malformed/missing property is intentionally ignored; it means
        // the request was real or an earlier filter failed before context setup.
        (req.getProperty(SYNTHETIC_TAINT_BAGGAGE_SCOPE_PROPERTY) as? Scope)?.close()
        MDC.remove(MDC_SYNTHETIC)
    }
}
