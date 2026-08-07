// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.authz

import jakarta.enterprise.util.Nonbinding
import jakarta.interceptor.InterceptorBinding

/**
 * Declarative fine-grained authorization on a JAX-RS / business method.
 * The companion interceptor (Phase 1 of ADR-0034 D5) builds an [AuthzQuery]
 * from the method arguments, consults the injected [PolicyDecisionPoint],
 * and rejects the call with `ForbiddenException` when the decision is deny.
 *
 * Usage — the K7 audit pattern from ADR-0018, but plumbed through the
 * libs API instead of every service rolling its own:
 *
 * ```kotlin
 * @POST
 * @Path("/{partyId}")
 * @RolesAllowed(Roles.OPERATOR)
 * @Authorize(action = "party.update", resource = "#partyId")
 * suspend fun updateParty(@PathParam("partyId") partyId: PartyId, …) { … }
 * ```
 *
 * The `@RolesAllowed` keeps the coarse role gate (cheap, Quarkus-native)
 * and `@Authorize` adds the resource-scoped check. Both must pass.
 *
 * Resource expression syntax: `#<paramName>` — names a method parameter
 * whose value becomes [AuthzQuery.resource]. The interceptor reads the
 * typesafe ID's class name to derive `ResourceRef.type` (`PartyId` →
 * "party"); for primitive ID types the convention is the noun in the
 * action prefix (`party.update` + `#partyId: String` → type = "party").
 *
 * The `#` prefix matches the spring-expression-language convention
 * already familiar from Spring `@PreAuthorize`. We support only the
 * single-token form to keep the parser obvious — anything richer goes
 * into the Rego policy.
 *
 * Lives in openbank-libs-**runtime**, not libs-domain: `@InterceptorBinding` is CDI, and
 * ADR-0122 puts framework-touching code on the runtime side of the split (#3670). The
 * package name is unchanged (`com.openbank.libs.authz`), so no consumer import moved.
 * The ports it names — [PolicyDecisionPoint], [AuthzQuery] — stay in libs-domain, which
 * is exactly the direction the dependency is allowed to run.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@InterceptorBinding
annotation class Authorize(
    /** Conventional `<aggregate>.<verb>` — see [PolicyDecisionPoint] kdoc. */
    @get:Nonbinding val action: String,
    /** `#<paramName>` reference to the resource id, or empty for non-scoped actions. */
    @get:Nonbinding val resource: String = "",
    /**
     * Names of request-context attributes to forward to the policy.
     * Currently recognised keys (the interceptor binds them):
     *   - `time-of-day`  → ISO instant
     *   - `client-ip`    → `X-Forwarded-For` first hop
     *   - `idempotency-key` → header value (audit correlation)
     * Unrecognised entries are ignored — additive list, no breakage.
     */
    @get:Nonbinding val attributes: Array<String> = [],
)
