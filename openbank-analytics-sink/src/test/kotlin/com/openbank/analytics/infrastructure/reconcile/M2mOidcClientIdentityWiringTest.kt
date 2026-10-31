// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.reconcile

import com.openbank.libs.testing.oidc.M2mOidcClientIdentityWiringConformance

/**
 * #10486 batch 5: analytics-sink's registry sweep lists accounts (`GET /api/v1/accounts/active`,
 * `account.list`) as its OWN principal, `service-account-openbank-analytics-sink` (ROLE_API only).
 * Two artefacts must agree and nothing at runtime says so when they do not: the rest-client must
 * select the NAMED oidc-client `m2m` (else the bearer is the shared `openbank-services` principal's,
 * which loses ROLE_OPERATOR in the final step and is then denied upstream), and
 * `quarkus.oidc-client.m2m` must exist with client `openbank-analytics-sink` and its own secret variable (a
 * missing named client fails the first call, not the build).
 */
class M2mOidcClientIdentityWiringTest : M2mOidcClientIdentityWiringConformance() {

    override val clientId = "openbank-analytics-sink"

    override val namedClients: List<Class<*>> = listOf(AccountRegistryClient::class.java)
}
