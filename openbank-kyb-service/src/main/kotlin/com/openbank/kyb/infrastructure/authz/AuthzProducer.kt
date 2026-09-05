// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.kyb.infrastructure.authz

import com.openbank.libs.authz.OpaSidecarPolicyDecisionPoint
import com.openbank.libs.authz.PolicyDecisionPoint
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Duration

@ApplicationScoped
class AuthzProducer {
    @ConfigProperty(name = "opa.url", defaultValue = OpaSidecarPolicyDecisionPoint.DEFAULT_BASE_URL)
    lateinit var opaUrl: String

    @ConfigProperty(name = "opa.path", defaultValue = OpaSidecarPolicyDecisionPoint.DEFAULT_QUERY_PATH)
    lateinit var opaPath: String

    /**
     * Declared as a [Duration], not a `Long` with a Kotlin initializer. A primitive field needs an
     * initializer to compile, and that initializer is exactly what the
     * `configproperty-kotlin-defaults` gate exists to stop: it generates a synthetic constructor
     * Arc builds the bean through, so the annotation is never applied and the field silently keeps
     * the literal whatever the environment says. `Duration` is an object, so `lateinit` works and
     * `defaultValue` is the only source of the fallback. SmallRye parses `PT0.5S` natively.
     */
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
