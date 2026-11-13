// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.securityscanner.config

import io.smallrye.config.EnvConfigSource
import io.smallrye.config.SmallRyeConfig
import io.smallrye.config.SmallRyeConfigBuilder
import io.smallrye.config.source.yaml.YamlConfigSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

/**
 * Issue #4942: `openbank.security.ict.incident` sat at end offset 0 because the deployed
 * manifest overrode `OPENBANK_OUTBOX_DISPATCH_ENABLED` to "false". `IctIncidentDurabilityIT`
 * proves the relay works when enabled; it cannot see the DEPLOYED value, nor which broker the
 * channel dials. Channel-level attributes override the global `kafka.*` defaults the manifest's
 * env sets, so a hard-coded `bootstrap.servers` on the channel would send the relay to a plaintext
 * address once dispatch is on. This pins the dispatch switch and the resolved channel config.
 */
class IctIncidentRelayDeployedConfigTest {
    private val manifest: Path =
        Path.of("..", "openbank-infra", "gitops", "components", "security-scanner", "security-scanner-service.yaml")
    private val appYaml: Path = Path.of("src", "main", "resources", "application.yaml")
    private val channel = "mp.messaging.outgoing.ict-incident-outbox-out"
    private val connector = "mp.messaging.connector.smallrye-kafka"

    @Suppress("UNCHECKED_CAST")
    private fun deployedEnv(): Map<String, String> {
        val docs = Files.newBufferedReader(manifest).use { Yaml().loadAll(it).toList() }
        val containers = docs.filterIsInstance<Map<String, Any?>>()
            .filter { it["kind"] == "Deployment" || it["kind"] == "Rollout" }
            .flatMap { d ->
                val spec = (d["spec"] as Map<String, Any?>)["template"] as Map<String, Any?>
                ((spec["spec"] as Map<String, Any?>)["containers"] as List<Map<String, Any?>>)
            }
        return containers.flatMap { (it["env"] as? List<Map<String, Any?>>).orEmpty() }
            .filter { it["value"] != null }
            .associate { it["name"].toString() to it["value"].toString() }
    }

    private fun resolvedConfig(): SmallRyeConfig {
        val env = deployedEnv().filterKeys { it.startsWith("KAFKA_") }
        return SmallRyeConfigBuilder()
            .addDefaultInterceptors()
            .withSources(YamlConfigSource(appYaml.toUri().toURL()))
            .withSources(EnvConfigSource(env, 300))
            .build()
    }

    @Test
    fun `gitops manifest does not disable the outbox dispatcher`() {
        assertThat(deployedEnv()["OPENBANK_OUTBOX_DISPATCH_ENABLED"]).isEqualTo("true")
    }

    @Test
    fun `application yaml enables the outbox dispatcher`() {
        val cfg = resolvedConfig()
        assertThat(cfg.getValue("openbank.outbox.dispatch-enabled", Boolean::class.java)).isTrue()
    }

    @Test
    fun `incident channel does not override the deployed broker with a hard-coded address`() {
        val cfg = resolvedConfig()
        val channelKeys = cfg.propertyNames.filter { it.startsWith("$channel.") }
        assertThat(channelKeys.filter { it.contains("bootstrap") })
            .describedAs("a channel-level bootstrap.servers (quoted or not) beats kafka.bootstrap.servers from the env")
            .isEmpty()
        assertThat(deployedEnv()["KAFKA_BOOTSTRAP_SERVERS"]).endsWith(":9093")
    }

    @Test
    fun `connector security protocol resolves to the deployed mTLS setting`() {
        val cfg = resolvedConfig()
        assertThat(cfg.getValue("$connector.security.protocol", String::class.java)).isEqualTo("SSL")
        assertThat(cfg.getValue("$connector.ssl.keystore.location", String::class.java))
            .isEqualTo(deployedEnv()["KAFKA_SSL_KEYSTORE_LOCATION"])
        assertThat(cfg.getValue("$connector.ssl.truststore.location", String::class.java))
            .isEqualTo(deployedEnv()["KAFKA_SSL_TRUSTSTORE_LOCATION"])
    }
}
