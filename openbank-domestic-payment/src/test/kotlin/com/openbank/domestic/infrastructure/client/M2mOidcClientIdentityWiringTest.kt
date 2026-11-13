// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.client

import com.openbank.libs.testing.oidc.M2mOidcClientIdentityWiringConformance
import io.quarkus.oidc.client.NamedOidcClient
import io.quarkus.oidc.client.filter.OidcClientFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #10486 batch 2: this service's money-path writes are made as its OWN principal,
 * `service-account-openbank-domestic-payment` (ROLE_API only), not the shared `openbank-services` one. Two
 * artefacts must agree and nothing at runtime says so when they do not: the rest-client / adapter
 * must select the NAMED oidc-client `m2m` (else the bearer is the shared principal's and the
 * upstream's identity rule never fires), and `quarkus.oidc-client.m2m` must exist with client
 * `openbank-domestic-payment` and its own secret variable (a missing named client fails the first call, not
 * the build).
 */
class M2mOidcClientIdentityWiringTest : M2mOidcClientIdentityWiringConformance() {

    override val clientId = "openbank-domestic-payment"

    override val namedClients: List<Class<*>> = listOf(AmlServiceClient::class.java)

    @Test
    fun `the transaction-service client mints its bearer from the named m2m oidc-client`() {
        // #13224: the token is attached by the filter on the generated client, not by hand in the adapter.
        val named = TransactionServiceClient::class.java.getAnnotation(OidcClientFilter::class.java)
        assertThat(named).describedAs("@OidcClientFilter on TransactionServiceClient").isNotNull
        assertThat(named.value).isEqualTo(oidcClientName)
        val qualifiers = SettlementAdapter::class.java.declaredConstructors.single().parameterAnnotations
            .flatMap { it.toList() }
            .filterIsInstance<NamedOidcClient>()
        assertThat(qualifiers).describedAs("SettlementAdapter must not mint a bearer by hand").isEmpty()
    }

    @Test
    fun `the account lookup stays on the shared default client`() {
        val qualifiers = AccountServiceClient::class.java.declaredConstructors.flatMap { c ->
            c.parameterAnnotations.flatMap { it.toList() }.filterIsInstance<NamedOidcClient>()
        }
        assertThat(qualifiers).describedAs("AccountServiceClient is a read edge, not migrated in batch 2").isEmpty()
    }
}
