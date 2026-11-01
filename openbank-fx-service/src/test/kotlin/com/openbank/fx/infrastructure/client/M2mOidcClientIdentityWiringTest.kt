// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.client

import com.openbank.libs.testing.oidc.M2mOidcClientIdentityWiringConformance

/**
 * #10486 batch 3: fx-service opens AML cases (`POST /api/v1/aml/cases`, `amlCase.create`) as its OWN
 * principal, `service-account-openbank-fx` (ROLE_API only), not the shared `openbank-services` one.
 * Two artefacts must agree and nothing at runtime says so when they do not: the rest-client must
 * select the NAMED oidc-client `m2m` (else the bearer is the shared principal's and aml-service's
 * named-caller check refuses it once the shared client loses ROLE_OPERATOR), and
 * `quarkus.oidc-client.m2m` must exist with client `openbank-fx` and its own secret variable (a
 * missing named client fails the first call, not the build).
 */
class M2mOidcClientIdentityWiringTest : M2mOidcClientIdentityWiringConformance() {

    override val clientId = "openbank-fx"

    override val namedClients: List<Class<*>> = listOf(AmlServiceClient::class.java)
}
