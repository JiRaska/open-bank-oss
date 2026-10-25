// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.client

import com.openbank.libs.testing.oidc.M2mOidcClientIdentityWiringConformance
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.junit.jupiter.api.Test

/**
 * #10486 batch 2: this service's money-path writes are made as its OWN principal,
 * `service-account-openbank-transaction` (ROLE_API only), not the shared `openbank-services` one. Two
 * artefacts must agree and nothing at runtime says so when they do not: the rest-client / adapter
 * must select the NAMED oidc-client `m2m` (else the bearer is the shared principal's and the
 * upstream's identity rule never fires), and `quarkus.oidc-client.m2m` must exist with client
 * `openbank-transaction` and its own secret variable (a missing named client fails the first call, not
 * the build).
 */
class M2mOidcClientIdentityWiringTest : M2mOidcClientIdentityWiringConformance() {

    override val clientId = "openbank-transaction"

    override val namedClients: List<Class<*>> = listOf(LedgerRestClient::class.java, BalanceCoverRestClient::class.java)

    @Test
    fun `the not-yet-migrated rest-clients keep the default (shared) client`() {
        listOf(FxServiceRestClient::class.java).forEach { client ->
            assertThat(providers(client))
                .describedAs("%s still uses the default (shared) client", client.simpleName)
                .contains(OidcClientRequestReactiveFilter::class.java)
            assertThat(client.getAnnotation(OidcClientFilter::class.java)).isNull()
        }
    }

    private fun providers(type: Class<*>): List<Class<*>> =
        type.getAnnotationsByType(RegisterProvider::class.java).map { it.value.java }
}
