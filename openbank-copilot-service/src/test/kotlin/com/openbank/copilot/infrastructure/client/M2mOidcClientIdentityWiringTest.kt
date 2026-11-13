// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.copilot.infrastructure.client

import com.openbank.libs.testing.oidc.M2mOidcClientIdentityWiringConformance
import org.assertj.core.api.Assertions.assertThat

/**
 * #10486 batch 6: copilot-service reads a party's credit profile from analytics-sink as its OWN
 * principal, `service-account-openbank-copilot` (ROLE_API only), which analytics-sink admits by name.
 * Two artefacts must agree and nothing at runtime says so when they do not: the rest-client must
 * select the NAMED oidc-client `m2m` (else the bearer is the shared `openbank-services` principal's,
 * which loses ROLE_OPERATOR in the final step and is then denied upstream), and
 * `quarkus.oidc-client.m2m` must exist with client `openbank-copilot` and its own secret variable (a
 * missing named client fails the first call, not the build).
 */
class M2mOidcClientIdentityWiringTest : M2mOidcClientIdentityWiringConformance() {

    override val clientId = "openbank-copilot"

    override val namedClients: List<Class<*>> = listOf(CreditProfileReadClient::class.java)

    override fun assertNamedClientExtras(named: Map<*, *>) {
        // The pod's QUARKUS_OIDC_AUTH_SERVER_URL is the CUSTOMER realm (inbound tokens); a machine
        // client lives in `openbank`, so the named client must not inherit that variable.
        assertThat(named["auth-server-url"] as String)
            .startsWith("\${OIDC_M2M_AUTH_SERVER_URL:")
            .endsWith("/realms/openbank}")
    }
}
