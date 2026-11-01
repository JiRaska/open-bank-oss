// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.client

import com.openbank.libs.testing.oidc.M2mOidcClientIdentityWiringConformance

/**
 * #10486 batch 5: party-service's GDPR Art. 15 aggregation reads accounts and transactions
 * (`account.list`, `transaction.list`) as its OWN principal, `service-account-openbank-party` (ROLE_API only).
 * Two artefacts must agree and nothing at runtime says so when they do not: the rest-client must
 * select the NAMED oidc-client `m2m` (else the bearer is the shared `openbank-services` principal's,
 * which loses ROLE_OPERATOR in the final step and is then denied upstream), and
 * `quarkus.oidc-client.m2m` must exist with client `openbank-party` and its own secret variable (a
 * missing named client fails the first call, not the build).
 */
class M2mOidcClientIdentityWiringTest : M2mOidcClientIdentityWiringConformance() {

    override val clientId = "openbank-party"

    override val namedClients: List<Class<*>> = listOf(
        AccountServiceRestClient::class.java,
        TransactionServiceRestClient::class.java,
        // #10486 batch 6: the GDPR Art. 15 card list (card-issuance card.list).
        CardServiceRestClient::class.java,
    )
}
