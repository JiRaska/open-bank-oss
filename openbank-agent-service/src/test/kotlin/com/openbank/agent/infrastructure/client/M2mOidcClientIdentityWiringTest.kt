// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.client

import com.openbank.libs.testing.oidc.M2mOidcClientIdentityWiringConformance

/**
 * #10486 batch 7: agent-service's transaction, SCT Inst and interest read tools present its OWN
 * principal, `service-account-openbank-agent` (ROLE_API only), and upstream grants it only what an
 * agents.yaml charter can reach (transaction and SCT Inst reads; interest nothing, since no charter holds
 * query.interest.readonly).
 * Two artefacts must agree and nothing at runtime says so when they do not: the rest-client must
 * select the NAMED oidc-client `m2m` (else the bearer is the shared `openbank-services` principal's,
 * which loses ROLE_OPERATOR in the final step and is then denied upstream), and
 * `quarkus.oidc-client.m2m` must exist with client `openbank-agent` and its own secret variable (a
 * missing named client fails the first call, not the build).
 */
class M2mOidcClientIdentityWiringTest : M2mOidcClientIdentityWiringConformance() {

    override val clientId = "openbank-agent"

    override val namedClients: List<Class<*>> = listOf(
        TransactionServiceClient::class.java,
        SepaInstantServiceClient::class.java,
        InterestServiceClient::class.java,
        AmlServiceClient::class.java,
        SanctionsServiceClient::class.java,
        DisputeServiceClient::class.java,
    )
}
