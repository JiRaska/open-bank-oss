// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.communication.infrastructure.approval

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
     * Declared as a [Duration], not a `Long` with a Kotlin initializer (the shape 38 other
     * services carry as accepted baseline debt — kyb-service's fix, mirrored here instead of
     * repeating the debt into a brand-new service). A primitive field needs an initializer to
     * compile, and that initializer is exactly what the `configproperty-kotlin-defaults` gate
     * exists to stop: it generates a synthetic constructor Arc builds the bean through, so the
     * annotation is never applied and the field silently keeps the literal whatever the
     * environment says. `Duration` is an object, so `lateinit` works and `defaultValue` is the
     * only source of the fallback. SmallRye parses `PT0.5S` natively.
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
