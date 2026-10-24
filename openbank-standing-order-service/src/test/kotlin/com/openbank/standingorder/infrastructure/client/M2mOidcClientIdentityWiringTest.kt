// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.infrastructure.client

import com.openbank.libs.testing.oidc.M2mOidcClientIdentityWiringConformance

/**
 * #10486 batch 1: this service's money-path writes are made as its OWN principal,
 * `service-account-openbank-standing-order` (ROLE_API only), not the shared `openbank-services` one. Two
 * artefacts must agree and nothing at runtime says so when they do not: the rest-client / adapter
 * must select the NAMED oidc-client `m2m` (else the bearer is the shared principal's and the
 * upstream's identity rule never fires), and `quarkus.oidc-client.m2m` must exist with client
 * `openbank-standing-order` and its own secret variable (a missing named client fails the first call, not
 * the build).
 */
class M2mOidcClientIdentityWiringTest : M2mOidcClientIdentityWiringConformance() {

    override val clientId = "openbank-standing-order"

    override val namedClients: List<Class<*>> = listOf(TransactionServiceClient::class.java, SepaPaymentClient::class.java)
}
