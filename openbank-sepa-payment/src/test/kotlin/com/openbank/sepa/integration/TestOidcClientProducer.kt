// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.integration

import io.mockk.every
import io.mockk.mockk
import io.quarkus.oidc.client.OidcClient
import io.quarkus.oidc.client.Tokens
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces

/**
 * Test-only [OidcClient] bean. The `%test` profile disables `quarkus.oidc-client`, so the default
 * `OidcClient` bean is absent — but [com.openbank.sepa.infrastructure.client.SchemeGatewayAdapter]
 * now resolves it on the scheme-submission path to attach the Bearer explicitly (ADR-0104 BUG #3).
 * This producer supplies a stub that yields a fixed token so the cross-service IT exercises that path.
 */
@ApplicationScoped
class TestOidcClientProducer {
    @Produces
    @ApplicationScoped
    fun oidcClient(): OidcClient {
        val tokens = mockk<Tokens> { every { accessToken } returns "it-test-token" }
        return mockk { every { getTokens() } returns Uni.createFrom().item(tokens) }
    }
}
