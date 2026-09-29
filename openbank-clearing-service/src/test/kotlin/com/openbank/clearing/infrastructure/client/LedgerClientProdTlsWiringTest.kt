// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.infrastructure.client

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/**
 * The ADR-0281 net-settlement journal posting reaches ledger-service over its private-CA mTLS
 * listener (8443, client auth REQUIRED). Two halves have to agree and nothing at runtime says so
 * when they do not: `quarkus.rest-client.ledger-service.tls-configuration-name` NAMES a bucket, and
 * `quarkus.tls.<bucket>` has to DEFINE it. Quarkus does not fail a rest-client whose named bucket is
 * missing — it falls back to the default TLS registry, which carries no client certificate, so the
 * listener refuses the handshake at the first real post and the journal never lands. That is silent
 * config drift, not a compile error, so it is asserted here rather than assumed.
 *
 * Deliberately scoped to `%prod`: local dev and every test fixture keep plain HTTP against
 * `http://localhost:8101`, because the client certificate only exists in the cluster.
 */
class LedgerClientProdTlsWiringTest {

    private val prod: Map<*, *> = run {
        val doc = Yaml().load<Map<String, Any>>(
            javaClass.classLoader.getResourceAsStream("application.yaml")!!,
        )
        doc["%prod"] as Map<*, *>
    }

    private fun quarkus(): Map<*, *> = prod["quarkus"] as Map<*, *>

    @Test
    fun `the ledger rest-client names a TLS bucket under prod`() {
        val restClient = (quarkus()["rest-client"] as Map<*, *>)["ledger-service"] as Map<*, *>
        assertThat(restClient["tls-configuration-name"]).isEqualTo(BUCKET)
    }

    @Test
    fun `the named bucket is defined and carries both the client key pair and the trust anchor`() {
        val bucket = (quarkus()["tls"] as Map<*, *>)[BUCKET] as Map<*, *>

        val client = ((bucket["key-store"] as Map<*, *>)["pem"] as Map<*, *>)["client"] as Map<*, *>
        assertThat(client["cert"]).isEqualTo("/mnt/ledger-tls/tls.crt")
        assertThat(client["key"]).isEqualTo("/mnt/ledger-tls/tls.key")

        val trust = (bucket["trust-store"] as Map<*, *>)["pem"] as Map<*, *>
        assertThat(trust["certs"]).isEqualTo("/mnt/ledger-tls/ca.crt")

        assertThat(bucket["protocols"]).isEqualTo("TLSv1.3")
    }

    private companion object {
        const val BUCKET = "ledger-authority"
    }
}
