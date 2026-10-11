// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.catalog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/** Guards the production client identity and CA registry binding separately from pension's server TLS. */
class CatalogClientProdTlsWiringTest {

    private val prod: Map<*, *> = run {
        val doc = Yaml().load<Map<String, Any>>(
            javaClass.classLoader.getResourceAsStream("application.yaml")!!,
        )
        doc["%prod"] as Map<*, *>
    }

    private fun quarkus(): Map<*, *> = prod["quarkus"] as Map<*, *>

    @Test
    fun `the catalog rest-client names a TLS bucket under prod`() {
        val restClient = (quarkus()["rest-client"] as Map<*, *>)["product-catalog"] as Map<*, *>
        assertThat(restClient["tls-configuration-name"]).isEqualTo(BUCKET)
    }

    @Test
    fun `the catalog bucket presents the CLIENT certificate, never this service's own server cert`() {
        val bucket = (quarkus()["tls"] as Map<*, *>)[BUCKET] as Map<*, *>

        val client = ((bucket["key-store"] as Map<*, *>)["pem"] as Map<*, *>)["client"] as Map<*, *>
        assertThat(client["cert"]).isEqualTo("/mnt/catalog-tls/tls.crt")
        assertThat(client["key"]).isEqualTo("/mnt/catalog-tls/tls.key")

        val trust = (bucket["trust-store"] as Map<*, *>)["pem"] as Map<*, *>
        assertThat(trust["certs"]).isEqualTo("/mnt/catalog-tls/ca.crt")

        // The server-cert mount must not leak into the client bucket.
        assertThat(listOf(client["cert"], client["key"], trust["certs"]))
            .noneMatch { (it as String).startsWith("/mnt/internal-tls/") }

        assertThat(bucket["protocols"]).isEqualTo("TLSv1.3")
    }

    @Test
    fun `identity and payment clients bind their authorities to the outbound client identity`() {
        val clients = quarkus()["rest-client"] as Map<*, *>
        val buckets = quarkus()["tls"] as Map<*, *>
        mapOf(
            "account" to "account-service",
            "party" to "party-service",
            "sca" to "sca-service",
            "sdd" to "sdd-service",
            "domestic-payment" to "domestic-payment",
            "standing-order" to "standing-order-service",
        ).forEach { (peer, configKey) ->
            val client = clients[configKey] as Map<*, *>
            val name = "$peer-authority"
            assertThat(client["tls-configuration-name"]).isEqualTo(name)
            val bucket = buckets[name] as Map<*, *>
            val key = ((bucket["key-store"] as Map<*, *>)["pem"] as Map<*, *>)["client"] as Map<*, *>
            val trust = (bucket["trust-store"] as Map<*, *>)["pem"] as Map<*, *>
            assertThat(key["cert"]).isEqualTo("/mnt/$peer-tls/tls.crt")
            assertThat(key["key"]).isEqualTo("/mnt/$peer-tls/tls.key")
            assertThat(trust["certs"]).isEqualTo("/mnt/$peer-tls/ca.crt")
            assertThat(bucket["protocols"]).isEqualTo("TLSv1.3")
        }
    }

    private companion object {
        const val BUCKET = "catalog-authority"
    }
}
