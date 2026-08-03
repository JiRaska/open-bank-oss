// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.flags

/**
 * Declarative feature gate on a JAX-RS / business method. The companion
 * [FeatureFlagInterceptor] evaluates [flag] through the injected [FeatureClient]
 * and, when it does not resolve on, short-circuits the call with
 * [FeatureDisabledException] (which a service maps to HTTP 404/501). Mirrors
 * `@Authorize`, but gates on *feature availability* rather than authorization.
 *
 * ```kotlin
 * @POST
 * @Path("/instant")
 * @FeatureFlag(flag = "sepa-instant-new-router", targetingKey = "#partyId")
 * suspend fun submitInstant(@PathParam("partyId") partyId: String, …) { … }
 * ```
 *
 * [targetingKey] uses the same `#<paramName>` convention as `@Authorize.resource`:
 * the named parameter's value becomes [EvalContext.targetingKey] so percentage /
 * A/B rollout buckets that subject deterministically. Empty = no targeting key
 * (the flag is evaluated context-free; only `STATIC`/`DISABLED` reasons apply).
 *
 * Fail-open at the annotation layer: if no [FeatureClient] bean is wired (a
 * service without a flagd sidecar), the interceptor proceeds rather than blanket-
 * disabling every gated method — consistent with the fail-static eval contract.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@jakarta.interceptor.InterceptorBinding
annotation class FeatureFlag(
    /** Flag key as declared in flag-as-code (e.g. `sepa-instant-new-router`). */
    @get:jakarta.enterprise.util.Nonbinding val flag: String,
    /** `#<paramName>` reference to the targeting key, or empty for a context-free gate. */
    @get:jakarta.enterprise.util.Nonbinding val targetingKey: String = "",
)

/**
 * Raised by [FeatureFlagInterceptor] when a `@FeatureFlag`-gated method is called
 * while the flag is off. Distinct from an authorization deny: the caller *may*
 * act, the capability is simply not enabled here/now. Services map it to 404
 * (hide existence) or 501 (not implemented yet) per their API contract.
 */
class FeatureDisabledException(val flag: String) : RuntimeException("feature '$flag' is not enabled")
