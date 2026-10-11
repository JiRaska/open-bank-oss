// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.catalog

import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.openbank.pension.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.Test
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Enables the same OIDC client filter used by the production catalog REST client. */
class CatalogClientOidcEnabledProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf("quarkus.oidc-client.enabled" to "true")
}

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(CatalogClientMtlsTestResource::class, restrictToAnnotatedClass = true)
@TestProfile(CatalogClientOidcEnabledProfile::class)
class CatalogClientMtlsIT {
    @RestClient
    @Inject
    lateinit var client: StrategyCatalogRestClient

    @Test
    fun `real catalog client mints OIDC token and succeeds with trusted mTLS peer`(): Unit = runBlocking {
        assertThat(client.offerings()).isEmpty()

        val servers = checkNotNull(CatalogTlsTestState.servers)
        servers.token.verify(1, postRequestedFor(urlEqualTo("/token")))
        servers.catalog.verify(
            1,
            getRequestedFor(urlEqualTo("/api/v2/offerings"))
                .withHeader("Authorization", equalTo("Bearer test-token")),
        )
    }
}

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(CatalogClientWrongCaTestResource::class, restrictToAnnotatedClass = true)
@TestProfile(CatalogClientOidcEnabledProfile::class)
class CatalogClientWrongCaIT {
    @RestClient
    @Inject
    lateinit var client: StrategyCatalogRestClient

    @Test
    fun `real catalog client rejects a server outside the configured trust store`() {
        assertCatalogHandshakeRejected { runBlocking { client.offerings() } }

        val servers = checkNotNull(CatalogTlsTestState.servers)
        servers.token.verify(1, postRequestedFor(urlEqualTo("/token")))
        servers.catalog.verify(0, getRequestedFor(urlEqualTo("/api/v2/offerings")))
    }
}

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(CatalogClientWrongHostnameTestResource::class, restrictToAnnotatedClass = true)
@TestProfile(CatalogClientOidcEnabledProfile::class)
class CatalogClientWrongHostnameIT {
    @RestClient
    @Inject
    lateinit var client: StrategyCatalogRestClient

    @Test
    fun `real catalog client rejects a trusted certificate for the wrong hostname`() {
        assertCatalogHandshakeRejected { runBlocking { client.offerings() } }

        val servers = checkNotNull(CatalogTlsTestState.servers)
        servers.token.verify(1, postRequestedFor(urlEqualTo("/token")))
        servers.catalog.verify(0, getRequestedFor(urlEqualTo("/api/v2/offerings")))
    }
}

private fun assertCatalogHandshakeRejected(call: () -> Unit) {
    val failure = catchThrowable { call() }
    assertThat(failure).isNotNull()
    val causes = generateSequence(checkNotNull(failure)) { it.cause }.toList()
    assertThat(causes).anyMatch { it is SSLHandshakeException || it is SSLPeerUnverifiedException }
}
