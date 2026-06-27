// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.flags

import jakarta.annotation.Priority
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import jakarta.interceptor.AroundInvoke
import jakarta.interceptor.Interceptor
import jakarta.interceptor.InvocationContext
import org.jboss.logging.Logger
import kotlin.reflect.jvm.kotlinFunction

/**
 * CDI interceptor that turns [FeatureFlag]-annotated methods into a call to the
 * injected [FeatureClient] (ADR-0067). Bound by the [FeatureFlag] annotation
 * itself (it is also an `@InterceptorBinding`); discovered via Jandex from the
 * libs JAR (ADR-0014), no `beans.xml` needed. Structurally a sibling of
 * `AuthorizeInterceptor`.
 *
 * Targeting-key extraction (`@FeatureFlag(targetingKey = "#partyId")`) reuses the
 * exact `#<paramName>` → Kotlin-reflection → arg-by-index path that
 * `AuthorizeInterceptor` uses for `resource`, so the two annotations behave
 * identically where they overlap.
 *
 * ### Fail-open at the gate (deliberately unlike authz)
 * If no [FeatureClient] bean is wired, the interceptor PROCEEDS. A missing flag
 * sidecar must not blanket-disable every gated endpoint — that would be a
 * self-inflicted outage. This mirrors the fail-static eval contract: absent flag
 * infrastructure means "behave as if the feature is on / unguarded", not "404
 * everything". When a client IS wired, an off flag yields [FeatureDisabledException].
 *
 * Priority matches `AuthorizeInterceptor`: after authn/authz have run, before
 * business logic — a feature can be gated *and* authorized, both must pass.
 */
@FeatureFlag(flag = "")
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_AFTER + 110)
class FeatureFlagInterceptor {
    private val log = Logger.getLogger(FeatureFlagInterceptor::class.java)

    // Lazy CDI Instance for the same reason as AuthorizeInterceptor.pdp: the libs
    // JAR is on every service's classpath, so a hard @Inject would force every
    // service to ship a FeatureClient bean before it builds. Instance<T> defers
    // resolution — a service with no @FeatureFlag method never runs this body.
    @Inject
    lateinit var flags: Instance<FeatureClient>

    @AroundInvoke
    fun gate(ctx: InvocationContext): Any? {
        val annotation = ctx.method.getAnnotation(FeatureFlag::class.java)
            ?: return ctx.proceed()

        if (!flags.isResolvable) {
            log.debugf(
                "no FeatureClient bean for @FeatureFlag %s.%s — proceeding (fail-open)",
                ctx.method.declaringClass.simpleName,
                ctx.method.name,
            )
            return ctx.proceed()
        }

        val evalContext = annotation.targetingKey.takeIf { it.isNotEmpty() }
            ?.let { extractTargetingKey(ctx, it) }
            ?.let { EvalContext(targetingKey = it) }
            ?: EvalContext.EMPTY

        if (flags.get().enabled(annotation.flag, evalContext)) {
            return ctx.proceed()
        }
        log.debugf("feature off: flag=%s key=%s — short-circuiting", annotation.flag, evalContext.targetingKey)
        throw FeatureDisabledException(annotation.flag)
    }

    private fun extractTargetingKey(ctx: InvocationContext, expr: String): String? {
        if (!expr.startsWith("#")) return null
        val paramName = expr.substring(1)
        val kFunction = ctx.method.kotlinFunction ?: return null
        // First parameter on instance functions is the receiver; skip it.
        val paramIndex = kFunction.parameters.drop(1).indexOfFirst { it.name == paramName }
        if (paramIndex < 0 || paramIndex >= ctx.parameters.size) return null
        return ctx.parameters[paramIndex]?.toString()
    }
}
