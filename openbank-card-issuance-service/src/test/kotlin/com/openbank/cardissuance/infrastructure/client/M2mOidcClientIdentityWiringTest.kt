// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.client

import com.openbank.libs.testing.oidc.M2mOidcClientIdentityWiringConformance

/**
 * card-issuance reads product configuration (`catalog.read`) as its OWN principal,
 * `service-account-openbank-card-issuance`, not the shared `openbank-services` one that
 * product-catalog refuses. The rest-client must select the NAMED oidc-client `m2m`, and
 * `quarkus.oidc-client.m2m` must exist with client `openbank-card-issuance` and its own secret
 * variable; neither mistake shows up before the first call.
 */
class M2mOidcClientIdentityWiringTest : M2mOidcClientIdentityWiringConformance() {

    override val clientId = "openbank-card-issuance"

    override val namedClients: List<Class<*>> = listOf(
        ProductCatalogClient::class.java,
    )
}
