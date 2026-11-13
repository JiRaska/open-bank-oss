// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.integration

import io.smallrye.config.EnvConfigSource
import io.smallrye.config.SmallRyeConfigBuilder
import io.smallrye.config.source.yaml.YamlConfigSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KybListenerRolloutConfigTest {
    @Test
    fun `the production image can still serve plain HTTP before the certificate is configured`() {
        val config = productionConfig()
        assertThat(config.getValue("quarkus.http.insecure-requests", String::class.java)).isEqualTo("enabled")
        assertThat(config.getValue("quarkus.http.ssl.client-auth", String::class.java)).isEqualTo("required")
        certificateFiles.keys.forEach { property ->
            assertThat(config.getOptionalValues(property, String::class.java)).isEmpty()
        }
    }

    @Test
    fun `production TLS files resolve from the KYB image aliases`() {
        val environment = certificateFiles.values.associate { (alias, file) -> alias to file }
        val config = productionConfig(environment)
        certificateFiles.forEach { (property, aliasAndFile) ->
            assertThat(config.getValues(property, String::class.java)).containsExactly(aliasAndFile.second)
        }
    }

    private fun productionConfig(environment: Map<String, String> = emptyMap()) = SmallRyeConfigBuilder()
        .addDefaultInterceptors()
        .withSources(
            YamlConfigSource(requireNotNull(javaClass.classLoader.getResource("application.yaml"))),
            EnvConfigSource(environment, 300),
        )
        .withProfile("prod")
        .build()

    private companion object {
        val certificateFiles = mapOf(
            "quarkus.http.ssl.certificate.files" to ("KYB_INTERNAL_TLS_CERT_FILES" to "/fixture/server.crt"),
            "quarkus.http.ssl.certificate.key-files" to ("KYB_INTERNAL_TLS_KEY_FILES" to "/fixture/server.key"),
            "quarkus.http.ssl.certificate.trust-store-files" to ("KYB_INTERNAL_TLS_TRUST_FILES" to "/fixture/ca.crt"),
        )
    }
}
