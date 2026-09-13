// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.wealth.infrastructure.authz

import com.openbank.libs.authz.OpaSidecarPolicyDecisionPoint
import com.openbank.libs.authz.PolicyDecisionPoint
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Duration

/**
 * The OPA timeout is a `Duration`, not a millisecond `Long`, and that is load-bearing.
 *
 * `@ConfigProperty` on a Kotlin PRIMITIVE field needs an initializer, and that initializer
 * generates the synthetic constructor Arc builds the bean through — so the annotation is never
 * applied and the field silently keeps the literal, whatever the environment says
 * (`configproperty-kotlin-defaults`). An object type takes `lateinit` and has no such problem.
 */
@ApplicationScoped
class AuthzProducer {
    @ConfigProperty(name = "opa.url", defaultValue = OpaSidecarPolicyDecisionPoint.DEFAULT_BASE_URL)
    lateinit var opaUrl: String

    @ConfigProperty(name = "opa.path", defaultValue = OpaSidecarPolicyDecisionPoint.DEFAULT_QUERY_PATH)
    lateinit var opaPath: String

    @ConfigProperty(name = "opa.timeout", defaultValue = "PT0.5S")
    lateinit var opaTimeout: Duration

    @Produces
    @ApplicationScoped
    fun policyDecisionPoint(): PolicyDecisionPoint = OpaSidecarPolicyDecisionPoint(
        baseUrl = opaUrl,
        queryPath = opaPath,
        timeout = opaTimeout,
    )
}
