// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.integration

import com.openbank.psd2.infrastructure.client.TppAuthorizationResponse
import com.openbank.psd2.infrastructure.client.TppRegistryRestClient
import io.quarkus.test.Mock
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * Test-only TPP registry: [AUTHORIZED_TPP] holds every role, any other id is rejected. Lets a
 * `@QuarkusTest` drive the real `EidasMtlsFilter` without a tpp-registry service (#10997).
 */
@Mock
@ApplicationScoped
@RestClient
class FakeTppRegistryRestClient : TppRegistryRestClient {
    override fun checkAuthorization(tppId: String, role: String): TppAuthorizationResponse =
        if (tppId == AUTHORIZED_TPP) {
            TppAuthorizationResponse(tppId, true, setOf("AISP", "PISP"), null)
        } else {
            TppAuthorizationResponse(tppId, false, emptySet(), "unknown TPP")
        }

    companion object {
        const val AUTHORIZED_TPP = "TPP-IT-AUTHORIZED"
    }
}
