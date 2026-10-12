// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.client

import io.quarkus.oidc.client.filter.OidcClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

class CardIssuanceOidcClientIdentityWiringTest {
    private val clients: Map<*, *> = run {
        val doc = Yaml().load<Map<String, Any>>(
            javaClass.classLoader.getResourceAsStream("application.yaml")!!,
        )
        (doc["quarkus"] as Map<*, *>)["oidc-client"] as Map<*, *>
    }

    @Test
    fun `issuer calls select the card-processing identity alone`() {
        val named = CardIssuanceClient::class.java.getAnnotation(OidcClientFilter::class.java)
        assertThat(named).isNotNull
        assertThat(named.value).isEqualTo("card-issuance")
        assertThat(providers(CardIssuanceClient::class.java))
            .doesNotContain(OidcClientRequestReactiveFilter::class.java)

        val issuer = clients["card-issuance"] as Map<*, *>
        assertThat(issuer["client-id"]).isEqualTo("openbank-card-processing")
        assertThat((issuer["grant"] as Map<*, *>)["type"]).isEqualTo("client")
        val credential = (issuer["credentials"] as Map<*, *>)["secret"] as String
        assertThat(credential).isEqualTo("\${OIDC_CARD_ISSUANCE_CLIENT_SECRET:UNCONFIGURED}")
    }

    @Test
    fun `transaction and fraud calls stay on the default client`() {
        assertThat(clients["client-id"]).isEqualTo("openbank-services")
        listOf(TransactionServiceClient::class.java, FraudServiceClient::class.java).forEach { client ->
            assertThat(client.getAnnotation(OidcClientFilter::class.java).value).isEmpty()
            assertThat(providers(client)).doesNotContain(OidcClientRequestReactiveFilter::class.java)
        }
    }

    private fun providers(type: Class<*>): List<Class<*>> =
        type.getAnnotationsByType(RegisterProvider::class.java).map { it.value.java }
}
