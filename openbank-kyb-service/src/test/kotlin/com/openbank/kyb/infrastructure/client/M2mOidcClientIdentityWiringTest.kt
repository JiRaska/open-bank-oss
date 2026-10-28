// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.client

import com.openbank.libs.testing.oidc.M2mOidcClientIdentityWiringConformance

/**
 * #10486 batch 3: kyb-service creates the entity party and grants its representation mandates
 * (`party.create`, `party.mandate.grant`) as its OWN principal, `service-account-openbank-kyb`
 * (ROLE_API only), not the shared `openbank-services` one. Two artefacts must agree and nothing at
 * runtime says so when they do not: the party rest-client must select the NAMED oidc-client `m2m`,
 * and `quarkus.oidc-client.m2m` must exist with client `openbank-kyb` and its own secret variable.
 */
class M2mOidcClientIdentityWiringTest : M2mOidcClientIdentityWiringConformance() {

    override val clientId = "openbank-kyb"

    override val namedClients: List<Class<*>> = listOf(PartyServiceRestClient::class.java)
}
