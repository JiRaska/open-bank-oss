// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.mtls

import io.smallrye.config.source.yaml.YamlConfigSource
import java.nio.file.Files
import java.nio.file.Path

/** Reads the `%prod` listener/auth keys [ProductionListenerProfile] applies (#12511). */
object ProductionListenerKeys {
    private const val PROD = "%prod."
    private val COPIED = listOf("quarkus.http.ssl.client-auth", "quarkus.http.insecure-requests", "quarkus.http.auth.")

    /** The `%prod` listener/auth keys of the module under test, profile prefix stripped. */
    fun read(applicationYaml: Path = Path.of("src/main/resources/application.yaml")): Map<String, String> {
        check(Files.isRegularFile(applicationYaml)) {
            "no $applicationYaml relative to ${Path.of("").toAbsolutePath()} — run from the service module"
        }
        val source = YamlConfigSource(applicationYaml.toUri().toURL())
        val keys = source.propertyNames
            .filter { it.startsWith(PROD) }
            .map { it.removePrefix(PROD) }
            .filter { key -> COPIED.any { key == it || (it.endsWith(".") && key.startsWith(it)) } }
            .associateWith { source.getValue(PROD + it) }
        check(keys.containsKey("quarkus.http.ssl.client-auth")) {
            "$applicationYaml has no %prod quarkus.http.ssl.client-auth — this service has no mTLS listener to test"
        }
        return keys
    }
}
