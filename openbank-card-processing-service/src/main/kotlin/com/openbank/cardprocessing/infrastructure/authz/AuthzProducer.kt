// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.authz

import com.openbank.libs.authz.OpaSidecarPolicyDecisionPoint
import com.openbank.libs.authz.PolicyDecisionPoint
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Duration

/**
 * CONSTRUCTOR injection, not `@ConfigProperty` fields with Kotlin initialisers.
 *
 * A field written `@ConfigProperty(...) var x: Long = 500` looks like a default and is a defect: the
 * initialiser generates a synthetic constructor, Arc builds the bean through it, and the annotation
 * is never applied — so the field silently keeps the literal whatever the environment says
 * (`check-configproperty-kotlin-defaults.py`). A primitive cannot be `lateinit`, so the field form
 * has no correct spelling here at all; a constructor parameter does, and the annotation's own
 * `defaultValue` is then the only default in play.
 */
@ApplicationScoped
class AuthzProducer(
    @ConfigProperty(name = "opa.url", defaultValue = OpaSidecarPolicyDecisionPoint.DEFAULT_BASE_URL)
    private val opaUrl: String,
    @ConfigProperty(name = "opa.path", defaultValue = OpaSidecarPolicyDecisionPoint.DEFAULT_QUERY_PATH)
    private val opaPath: String,
    @ConfigProperty(name = "opa.timeout-ms", defaultValue = "500")
    private val opaTimeoutMs: Long,
) {

    @Produces
    @ApplicationScoped
    fun policyDecisionPoint(): PolicyDecisionPoint = OpaSidecarPolicyDecisionPoint(
        baseUrl = opaUrl,
        queryPath = opaPath,
        timeout = Duration.ofMillis(opaTimeoutMs),
    )
}
