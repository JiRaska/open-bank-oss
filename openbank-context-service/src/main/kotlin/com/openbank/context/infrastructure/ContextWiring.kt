// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import com.openbank.libs.authz.OpaSidecarPolicyDecisionPoint
import com.openbank.libs.authz.PolicyDecisionPoint
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.Duration

@ApplicationScoped
class ContextWiring {
    @ConfigProperty(name = "opa.url")
    lateinit var opaUrl: String

    @ConfigProperty(name = "opa.path")
    lateinit var opaPath: String

    @ConfigProperty(name = "opa.timeout")
    lateinit var opaTimeout: Duration

    @Produces @ApplicationScoped
    fun pdp(): PolicyDecisionPoint = OpaSidecarPolicyDecisionPoint(opaUrl, opaPath, opaTimeout)

    @Produces @ApplicationScoped
    fun clock(): Clock = Clock.systemUTC()
}
