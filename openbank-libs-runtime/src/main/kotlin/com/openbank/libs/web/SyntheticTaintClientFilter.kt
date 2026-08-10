// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.web

import com.openbank.libs.synthetic.SyntheticTaint
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.client.ClientRequestFilter
import org.jboss.logging.MDC

/**
 * Carries the synthetic taint across a service-to-service hop (ADR-0252 phase 1, issue #4348).
 *
 * [SyntheticTaintRequestFilter] decides whether an inbound request is a canary's. Without this
 * filter that decision dies at the first outbound call: service A knows the flow is synthetic,
 * service B does not, and the same customer journey is half tainted. Every downstream write then
 * lands in the regulatory aggregates the taint exists to keep it out of.
 *
 * ## Registration is explicit, and that is a real limitation
 *
 * The fleet convention is `@RegisterProvider(...)` on each `@RegisterRestClient` interface (see
 * `ProductCatalogHostHeaderFilter` and the `OidcClientRequestReactiveFilter` registrations), not
 * global `@Provider` discovery — there is no `@Provider`-annotated client filter anywhere in this
 * tree, so nothing here demonstrates that global registration works, and claiming it would be a
 * guess about the framework rather than a fact about the repo.
 *
 * So a client interface that does not register this filter drops the taint. That failure is in the
 * SAFE direction — a dropped taint means the downstream hop is treated as real, so synthetic
 * activity reaches an aggregate it should not, which is visible and bounded, rather than real
 * activity vanishing from one, which is neither. Rolling registration out across the client
 * interfaces, with a gate so a new client cannot forget, is tracked in #4348.
 *
 * ## Why MDC is the source
 *
 * The inbound filter publishes its decision to the JAX-RS `ContainerRequestContext`, which a client
 * filter cannot see, and to the MDC, which it can. A reactive hop that loses MDC therefore drops
 * the taint — again the safe direction. A request-scoped holder with explicit context propagation
 * would be tighter, and is the follow-up; it is not free, because a request-scoped bean injected
 * into a client filter that runs outside a request context is an error rather than a `false`.
 */
@ApplicationScoped
class SyntheticTaintClientFilter : ClientRequestFilter {

    override fun filter(requestContext: ClientRequestContext) {
        // Only ever ADDS the header, never removes one: an outbound call made by a canary-owned
        // service account may legitimately carry its own taint that this hop knows nothing about.
        if (MDC.get(MDC_SYNTHETIC) == "true") {
            requestContext.headers.putSingle(SyntheticTaint.KAFKA_HEADER, SyntheticTaint.headerValue())
        }
    }
}
