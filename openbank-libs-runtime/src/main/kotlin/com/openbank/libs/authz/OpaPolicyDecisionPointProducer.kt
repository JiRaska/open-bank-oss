// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.authz

import io.quarkus.arc.properties.IfBuildProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Duration

/**
 * Fleet-default [PolicyDecisionPoint] producer for [AuthorizeInterceptor]: wires the per-service
 * OPA sidecar ([OpaSidecarPolicyDecisionPoint]) from `opa.url`, `opa.path` and `opa.timeout-ms`,
 * with the same defaults every per-service `AuthzProducer` copy used. `opa.path` is read from
 * config, never hardcoded, because services point it at their own policy package.
 *
 * Deliberately NOT `@DefaultBean`: a second `PolicyDecisionPoint` producer in a service's
 * `src/main` (a stale copy of the old per-service `AuthzProducer`, a leftover `@Alternative`)
 * must fail the build as an ambiguous CDI dependency, loudly, at `quarkusBuild` time — not be
 * silently displaced in favour of whichever one CDI happens to pick. `@DefaultBean` would have
 * hidden exactly that mistake.
 *
 * One deliberate qualifier:
 *  - `@IfBuildProperty(openbank.authz.opa-pdp-producer.enabled=true)`, OFF when missing — the
 *    libs JAR is on every service's classpath, and several services use `@Authorize` with NO PDP
 *    bean today, where the interceptor's `pdp_unconfigured` branch decides the outcome. An
 *    unconditional bean would silently switch those services onto live OPA decisions. A service
 *    opts in explicitly in its `application.yaml`.
 */
@ApplicationScoped
class OpaPolicyDecisionPointProducer {
    @ConfigProperty(name = "opa.url", defaultValue = OpaSidecarPolicyDecisionPoint.DEFAULT_BASE_URL)
    lateinit var opaUrl: String

    @ConfigProperty(name = "opa.path", defaultValue = OpaSidecarPolicyDecisionPoint.DEFAULT_QUERY_PATH)
    lateinit var opaPath: String

    // This is FIELD injection (a class-body property, not a constructor parameter), so a Kotlin
    // default here would NOT trip the synthetic-constructor trap in
    // rules.yaml: configproperty_kotlin_defaults / check-configproperty-kotlin-defaults.py — that
    // gate is scoped to constructor parameters only, and CDI overwrites a defaulted field via field
    // injection after construction regardless. `lateinit` + the annotation's `defaultValue` is used
    // here purely for consistency with `opaUrl`/`opaPath` above, not because a field default would
    // have discarded config (it would not have — the deleted per-service `AuthzProducer` copies
    // used exactly that shape and honoured `opa.timeout-ms` correctly).
    @ConfigProperty(name = "opa.timeout-ms", defaultValue = DEFAULT_OPA_TIMEOUT_MS_STR)
    lateinit var opaTimeoutMs: java.lang.Long

    @Produces
    @ApplicationScoped
    @IfBuildProperty(name = ENABLED_PROPERTY, stringValue = "true", enableIfMissing = false)
    fun policyDecisionPoint(): PolicyDecisionPoint = OpaSidecarPolicyDecisionPoint(
        baseUrl = opaUrl,
        queryPath = opaPath,
        timeout = Duration.ofMillis(opaTimeoutMs.toLong()),
    )

    companion object {
        const val ENABLED_PROPERTY = "openbank.authz.opa-pdp-producer.enabled"
        private const val DEFAULT_OPA_TIMEOUT_MS_STR = "500"
    }
}
