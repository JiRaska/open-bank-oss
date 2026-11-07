// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.mcp.infrastructure.read

import com.openbank.libs.testing.oidc.M2mOidcClientIdentityWiringConformance

/**
 * #10486 batch 7: mcp-service's transaction list and payment-confirmation reads present its OWN
 * principal, `service-account-openbank-mcp` (ROLE_API only), and upstream grants it only what the
 * mcp-anonymous charter can reach (transaction.list; the payment confirmation nothing yet).
 * Two artefacts must agree and nothing at runtime says so when they do not: the rest-client must
 * select the NAMED oidc-client `m2m` (else the bearer is the shared `openbank-services` principal's,
 * which loses ROLE_OPERATOR in the final step and is then denied upstream), and
 * `quarkus.oidc-client.m2m` must exist with client `openbank-mcp` and its own secret variable (a
 * missing named client fails the first call, not the build).
 */
class M2mOidcClientIdentityWiringTest : M2mOidcClientIdentityWiringConformance() {

    override val clientId = "openbank-mcp"

    override val namedClients: List<Class<*>> = listOf(
        TransactionServiceClient::class.java,
        SepaPaymentServiceClient::class.java,
        DomesticPaymentServiceClient::class.java,
        StatementServiceClient::class.java,
    )
}
