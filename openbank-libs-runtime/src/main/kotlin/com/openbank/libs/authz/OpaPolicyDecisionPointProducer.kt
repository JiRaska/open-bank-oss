// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.authz

import io.quarkus.arc.DefaultBean
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
 * Two deliberate qualifiers:
 *  - `@DefaultBean` — any service-level or test-level `PolicyDecisionPoint` bean (an
 *    `@Alternative`, a `@Mock`, a service that keeps its own producer) displaces this one.
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

    // A Kotlin default here (`= 500L`) would generate a synthetic constructor and silently
    // discard whatever `opa.timeout-ms` says (rules.yaml: configproperty_kotlin_defaults). Use
    // `lateinit` over the boxed type instead, exactly as `opaUrl`/`opaPath` above rely solely on
    // the annotation's defaultValue.
    @ConfigProperty(name = "opa.timeout-ms", defaultValue = DEFAULT_OPA_TIMEOUT_MS_STR)
    lateinit var opaTimeoutMs: java.lang.Long

    @Produces
    @ApplicationScoped
    @DefaultBean
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
