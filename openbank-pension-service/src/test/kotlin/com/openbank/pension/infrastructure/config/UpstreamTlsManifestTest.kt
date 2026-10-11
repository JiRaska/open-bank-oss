// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.config

import io.smallrye.config.source.yaml.YamlConfigSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every peer service pension-service calls is a money-path or identity hop, so the DEPLOYED URL must
 * be the peer's internal-TLS listener (https, 8443) — the `%prod` `*-authority` bucket only applies
 * client-certificate and hostname checks to a connection that is TLS in the first place. A plain
 * `http://…:<app port>` URL silently bypasses all of it (the pension-fund hop shipped that way).
 * Keycloak and the OTLP collector are platform endpoints reached over HTTP fleet-wide, not peers.
 */
class UpstreamTlsManifestTest {
    private val manifest = File("../openbank-infra/gitops/components/pension/pension-service.yaml")

    @Test
    fun `every in-cluster peer URL in the deployment is the internal TLS listener`() {
        // HTTP(S) peers only; Keycloak and the OTLP collector are platform endpoints, not peer services.
        val urls = Regex("""value:\s*"?(https?://[^\s"]+\.svc[^\s"]*)""").findAll(manifest.readText())
            .map { it.groupValues[1] }
            .filterNot { it.contains(".iam.svc") || it.contains(".observability.svc") }
            .toList()
        assertThat(urls).contains("https://pension-fund-service.pension-fund.svc:8443")
        assertThat(urls).allSatisfy { assertThat(it).startsWith("https://").endsWith(":8443") }
    }

    @Test
    fun `the pension-fund client uses its own mTLS bucket with hostname verification left on`() {
        val source = YamlConfigSource(
            requireNotNull(javaClass.classLoader.getResource("application.yaml")) { "application.yaml on classpath" },
        )
        assertThat(source.getValue("%prod.quarkus.rest-client.pension-fund-service.tls-configuration-name"))
            .isEqualTo("fund-authority")
        assertThat(source.getValue("%prod.quarkus.tls.fund-authority.hostname-verification-algorithm")).isNull()
        assertThat(source.getValue("%prod.quarkus.tls.fund-authority.key-store.pem.client.cert")).isNotNull()
    }
}
