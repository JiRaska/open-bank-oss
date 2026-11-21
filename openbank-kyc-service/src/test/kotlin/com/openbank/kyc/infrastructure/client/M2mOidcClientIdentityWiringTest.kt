// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.client

import com.openbank.libs.testing.oidc.M2mOidcClientIdentityWiringConformance
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.junit.jupiter.api.Test

/**
 * #15723: kyc-service screens against sanctions-service and reads the party register as its OWN
 * principal, `service-account-openbank-kyc` (ROLE_API only), not the shared `openbank-services`
 * one. Two artefacts must agree and nothing at runtime says so when they do not: each migrated
 * rest-client must select the NAMED oidc-client `m2m`, and `quarkus.oidc-client.m2m` must exist
 * with client `openbank-kyc` and its own secret variable.
 */
class M2mOidcClientIdentityWiringTest : M2mOidcClientIdentityWiringConformance() {

    override val clientId = "openbank-kyc"

    override val namedClients: List<Class<*>> = listOf(
        SanctionsServiceClient::class.java,
        SanctionsCustomerScreeningClient::class.java,
        PartyServiceClient::class.java,
    )

    /**
     * The case-open route is ROLE_OPERATOR-gated and `openbank-kyc` holds ROLE_API only, so moving
     * this client onto the named one would turn a working call into a 403. It moves only together
     * with a grant on the coordinator side.
     */
    @Test
    fun `the case-coordinator client stays on the default client`() {
        val client = CaseCoordinatorClient::class.java
        assertThat(client.getAnnotation(OidcClientFilter::class.java)).isNull()
        assertThat(client.getAnnotationsByType(RegisterProvider::class.java).map { it.value.java })
            .contains(OidcClientRequestReactiveFilter::class.java)
    }
}
