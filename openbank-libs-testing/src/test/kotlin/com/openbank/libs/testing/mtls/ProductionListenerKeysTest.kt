// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.mtls

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ProductionListenerKeysTest {

    @Test
    fun `copies the prod listener and auth keys and nothing else`(@TempDir dir: Path) {
        val yaml = Files.writeString(
            dir.resolve("application.yaml"),
            """
            quarkus:
              http:
                port: 8080
            "%prod":
              quarkus:
                datasource:
                  jdbc:
                    url: jdbc:postgresql://db/x
                http:
                  insecure-requests: enabled
                  ssl:
                    client-auth: required
                  auth:
                    permission:
                      bearer-only:
                        paths: /api/*
                        policy: permit
                        auth-mechanism: bearer
            """.trimIndent(),
        )
        assertThat(ProductionListenerKeys.read(yaml)).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "quarkus.http.ssl.client-auth" to "required",
                "quarkus.http.insecure-requests" to "enabled",
                "quarkus.http.auth.permission.bearer-only.paths" to "/api/*",
                "quarkus.http.auth.permission.bearer-only.policy" to "permit",
                "quarkus.http.auth.permission.bearer-only.auth-mechanism" to "bearer",
            ),
        )
    }

    @Test
    fun `refuses a service whose prod profile has no client-auth`(@TempDir dir: Path) {
        val yaml = Files.writeString(
            dir.resolve("application.yaml"),
            "\"%prod\":\n  quarkus:\n    http:\n      port: 1\n",
        )
        assertThatThrownBy { ProductionListenerKeys.read(yaml) }
            .hasMessageContaining("no %prod quarkus.http.ssl.client-auth")
    }
}
