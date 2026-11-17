// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.infrastructure.client

import com.openbank.libs.testing.oidc.M2mOidcClientIdentityWiringConformance

/**
 * The account-ownership lookup is made as consent-service's OWN principal,
 * `service-account-openbank-consent` (ROLE_API only), which account_rest_ext.rego admits to
 * `account.verifyOwnership` alone. The client must select the named oidc-client `m2m`, and
 * `quarkus.oidc-client.m2m` must carry client `openbank-consent` with its own secret variable.
 */
class M2mOidcClientIdentityWiringTest : M2mOidcClientIdentityWiringConformance() {

    override val clientId = "openbank-consent"

    override val namedClients: List<Class<*>> = listOf(AccountOwnershipClient::class.java)
}
