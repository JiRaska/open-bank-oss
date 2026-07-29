// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.client

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.Test

/**
 * Proves the KEDA-interceptor routing contract end to end (#668 / ADR-0083 T1 re-entry): with
 * `product-catalog-api.host-override` set, the outgoing request carries that exact `Host`
 * header even though the connection URL points elsewhere (the interceptor proxy in production,
 * WireMock here). If a future Quarkus/Vert.x upgrade stops honouring an explicitly set Host
 * header, this test goes red instead of the onboarding path silently degrading again — the
 * interceptor would see the proxy's own host, match no HTTPScaledObject, and 404 every read.
 */
@QuarkusTest
@TestProfile(ProductCatalogHostHeaderIT.Profile::class)
@QuarkusTestResource(ProductCatalogHostHeaderIT.CatalogStub::class)
class ProductCatalogHostHeaderIT {

    @Inject
    @RestClient
    lateinit var client: ProductCatalogClient

    @Test
    fun `the configured Host override reaches the wire`(): Unit = runBlocking {
        val response = client.getById("11111111-1111-1111-1111-111111111111").awaitSuspending()
        assertThat(response.code).isEqualTo("RAMCOVA_SMLOUVA_CS")

        server.verify(
            getRequestedFor(urlPathEqualTo("/api/v1/products/11111111-1111-1111-1111-111111111111"))
                .withHeader("Host", equalTo(EXPECTED_HOST)),
        )
    }

    class Profile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "product-catalog-api.host-override" to EXPECTED_HOST,
            // The OIDC filter needs a token endpoint even though the stub does not check auth.
            "quarkus.oidc-client.auth-server-url" to "http://localhost:1/unused",
            "quarkus.oidc-client.client-id" to "openbank-services",
            "quarkus.oidc-client.credentials.secret" to "test",
            "quarkus.oidc.auth-server-url" to "http://localhost:1/unused",
        )
    }

    class CatalogStub : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            server = WireMockServer(options().dynamicPort())
            server.start()
            server.stubFor(
                post(urlEqualTo("/token")).willReturn(
                    okJson("""{"access_token":"t","token_type":"Bearer","expires_in":300}"""),
                ),
            )
            server.stubFor(
                get(urlPathEqualTo("/api/v1/products/11111111-1111-1111-1111-111111111111")).willReturn(
                    okJson(
                        """{"id":"11111111-1111-1111-1111-111111111111","code":"RAMCOVA_SMLOUVA_CS","name":"R","termsAndConditions":[]}""",
                    ),
                ),
            )
            return mapOf(
                "quarkus.rest-client.product-catalog-api.url" to server.baseUrl(),
                "quarkus.oidc-client.discovery-enabled" to "false",
                "quarkus.oidc-client.token-path" to "/token",
                "quarkus.oidc-client.auth-server-url" to server.baseUrl(),
                "quarkus.oidc.auth-server-url" to server.baseUrl(),
            )
        }

        override fun stop() {
            server.stop()
        }
    }

    companion object {
        private const val EXPECTED_HOST = "product-catalog.accounts.svc"
        private lateinit var server: WireMockServer
    }
}
