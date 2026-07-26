// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

import com.openbank.libs.authz.OpaSidecarPolicyDecisionPoint
import com.openbank.libs.authz.PolicyDecisionPoint
import io.quarkus.arc.DefaultBean
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

    @ConfigProperty(name = "opa.timeout-ms", defaultValue = "500")
    var opaTimeoutMs: Long = DEFAULT_OPA_TIMEOUT_MS

    /**
     * `@DefaultBean` so a test JVM can displace this UNCONDITIONALLY (#2494). Without it the only
     * thing keeping the real sidecar client out of a `@QuarkusTest` is alternative-vs-producer
     * resolution precedence — which does hold today, but is precedence nobody declared and nothing
     * asserts, and the failure when it stops holding is a bootstrap timeout against
     * `localhost:8181` rather than anything that names a bean. Same shape as
     * `openbank-agent-service`'s `DenyByDefaultPolicyDecisionPoint`. `PdpBeanSelectionIT` asserts
     * the resulting binding by type.
     */
    @Produces
    @ApplicationScoped
    @DefaultBean
    fun policyDecisionPoint(): PolicyDecisionPoint = OpaSidecarPolicyDecisionPoint(
        baseUrl = opaUrl,
        queryPath = opaPath,
        timeout = Duration.ofMillis(opaTimeoutMs),
    )

    private companion object {
        const val DEFAULT_OPA_TIMEOUT_MS = 500L
    }
}
