// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.client

import com.openbank.libs.testing.oidc.M2mOidcClientIdentityWiringConformance
import io.quarkus.oidc.client.NamedOidcClient
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.junit.jupiter.api.Test

/**
 * #10486 batch 1: this service's money-path writes are made as its OWN principal,
 * `service-account-openbank-sepa-payment` (ROLE_API only), not the shared `openbank-services` one. Two
 * artefacts must agree and nothing at runtime says so when they do not: the rest-client / adapter
 * must select the NAMED oidc-client `m2m` (else the bearer is the shared principal's and the
 * upstream's identity rule never fires), and `quarkus.oidc-client.m2m` must exist with client
 * `openbank-sepa-payment` and its own secret variable (a missing named client fails the first call, not
 * the build).
 */
class M2mOidcClientIdentityWiringTest : M2mOidcClientIdentityWiringConformance() {

    override val clientId = "openbank-sepa-payment"

    override val namedClients: List<Class<*>> = listOf(AmlServiceClient::class.java)

    @Test
    fun `the transaction-service client mints its bearer from the named m2m oidc-client`() {
        // #13224: the token is attached by the filter on the generated client, not by hand in the adapters.
        val named = TransactionServiceClient::class.java.getAnnotation(OidcClientFilter::class.java)
        assertThat(named).describedAs("@OidcClientFilter on TransactionServiceClient").isNotNull
        assertThat(named.value).isEqualTo(oidcClientName)
        assertThat(
            TransactionServiceClient::class.java.getAnnotationsByType(RegisterProvider::class.java)
                .map { it.value.java },
        )
            .describedAs("the default-client filter would re-attach the shared principal's token")
            .doesNotContain(OidcClientRequestReactiveFilter::class.java)
        listOf(SettlementAdapter::class.java, ReversalAdapter::class.java).forEach { adapter ->
            val qualifiers = adapter.declaredConstructors.single().parameterAnnotations
                .flatMap { it.toList() }
                .filterIsInstance<NamedOidcClient>()
            assertThat(qualifiers).describedAs("%s must not mint a bearer by hand", adapter.simpleName).isEmpty()
        }
    }
}
