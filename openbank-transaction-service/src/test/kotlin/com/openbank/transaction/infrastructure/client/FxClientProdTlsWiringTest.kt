// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.client

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/**
 * The cross-currency rate lookup reaches fx-service over its private-CA mTLS listener (8443, client
 * auth REQUIRED). Two halves have to agree and nothing at runtime says so when they do not:
 * `quarkus.rest-client.fx-service.tls-configuration-name` NAMES a bucket, and `quarkus.tls.<bucket>`
 * has to DEFINE it. Quarkus does not fail a rest-client whose named bucket is missing — it falls back
 * to the default TLS registry, which carries no client certificate, so fx-service refuses the
 * handshake and every rate lookup fails. That is silent config drift, not a compile error.
 *
 * The second assertion is the one worth having: this pod mounts TWO private-CA secrets — its own
 * SERVER certificate for the 8443 listener at `/mnt/internal-tls`, and its CLIENT identity for
 * calling fx-service at `/mnt/fx-tls`. Pointing the client bucket at the server mount would present
 * the wrong certificate, and both paths exist, so nothing but this assertion distinguishes them.
 *
 * Deliberately scoped to `%prod`: local dev and every test fixture keep plain HTTP against
 * `http://localhost:8119`, because the client certificate only exists in the cluster.
 */
class FxClientProdTlsWiringTest {

    private val prod: Map<*, *> = run {
        val doc = Yaml().load<Map<String, Any>>(
            javaClass.classLoader.getResourceAsStream("application.yaml")!!,
        )
        doc["%prod"] as Map<*, *>
    }

    private fun quarkus(): Map<*, *> = prod["quarkus"] as Map<*, *>

    @Test
    fun `the fx rest-client names a TLS bucket under prod`() {
        val restClient = (quarkus()["rest-client"] as Map<*, *>)["fx-service"] as Map<*, *>
        assertThat(restClient["tls-configuration-name"]).isEqualTo(BUCKET)
    }

    @Test
    fun `the fx bucket presents the CLIENT certificate, never this service's own server cert`() {
        val bucket = (quarkus()["tls"] as Map<*, *>)[BUCKET] as Map<*, *>

        val client = ((bucket["key-store"] as Map<*, *>)["pem"] as Map<*, *>)["client"] as Map<*, *>
        assertThat(client["cert"]).isEqualTo("/mnt/fx-tls/tls.crt")
        assertThat(client["key"]).isEqualTo("/mnt/fx-tls/tls.key")

        val trust = (bucket["trust-store"] as Map<*, *>)["pem"] as Map<*, *>
        assertThat(trust["certs"]).isEqualTo("/mnt/fx-tls/ca.crt")

        // The server-cert mount must not leak into the client bucket.
        assertThat(listOf(client["cert"], client["key"], trust["certs"]))
            .noneMatch { (it as String).startsWith("/mnt/internal-tls/") }

        assertThat(bucket["protocols"]).isEqualTo("TLSv1.3")
    }

    private companion object {
        const val BUCKET = "fx-authority"
    }
}
