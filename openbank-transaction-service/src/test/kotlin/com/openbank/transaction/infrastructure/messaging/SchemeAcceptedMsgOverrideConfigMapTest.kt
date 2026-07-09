// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.messaging

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.Properties

/**
 * Guards the `transaction-service-msg-override` gitops ConfigMap (issue #686 /
 * ADR-0137): SmallRye's YAML config source unconditionally quotes any leaf map key
 * containing a literal dot when flattening YAML, so `group.id` / `auto.offset.reset`
 * / `dead-letter-queue.topic` written as plain YAML keys under
 * `mp.messaging.incoming.payment-scheme-accepted` in application.yaml silently never
 * resolve. The ConfigMap at
 * `openbank-infra/gitops/components/payments/transaction-service-msg-override.yaml`
 * works around this by carrying the same values as a flat Java `.properties` file
 * (mounted via QUARKUS_CONFIG_LOCATIONS, config_ordinal=500), where a dotted key is
 * just a literal property name — no flattening ambiguity.
 *
 * This test does not boot the service; it statically parses the ConfigMap YAML and
 * asserts `override.properties` carries the exact three keys/values that
 * application.yaml intends for this channel, so a future edit to either file can't
 * silently drift the two apart.
 */
class SchemeAcceptedMsgOverrideConfigMapTest {

    private val channelPrefix = "mp.messaging.incoming.payment-scheme-accepted"

    private fun repoRoot(): File {
        // Note: openbank-transaction-service/ carries its OWN settings.gradle.kts (for the
        // per-service Docker build context that copies only openbank-libs + this service), so
        // that alone isn't a reliable root marker. The monorepo root is the ancestor that also
        // has an openbank-infra/ sibling directory (gitops manifests live only there).
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "openbank-infra").isDirectory || !File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error(
                    "Could not locate monorepo root (settings.gradle.kts + openbank-infra/) " +
                        "above ${System.getProperty("user.dir")}",
                )
        }
        return dir
    }

    private fun overrideProperties(): Properties {
        val configMapFile = File(
            repoRoot(),
            "openbank-infra/gitops/components/payments/transaction-service-msg-override.yaml",
        )
        assertThat(configMapFile).exists()

        @Suppress("UNCHECKED_CAST")
        val configMap = Yaml().load<Map<String, Any>>(configMapFile.readText())

        @Suppress("UNCHECKED_CAST")
        val data = configMap["data"] as Map<String, Any>
        val overridePropertiesText = data["override.properties"] as String

        val properties = Properties()
        properties.load(overridePropertiesText.reader())
        return properties
    }

    @Test
    fun `override properties carry the config_ordinal that outranks baked application yaml`() {
        val properties = overrideProperties()

        assertThat(properties.getProperty("config_ordinal")).isEqualTo("500")
    }

    @Test
    fun `override properties carry group id matching the KafkaUser ACL grant`() {
        val properties = overrideProperties()

        assertThat(properties.getProperty("$channelPrefix.group.id"))
            .isEqualTo("transaction-scheme-accepted-cg")
    }

    @Test
    fun `override properties carry the dead-letter-queue topic`() {
        val properties = overrideProperties()

        assertThat(properties.getProperty("$channelPrefix.dead-letter-queue.topic"))
            .isEqualTo("payment.scheme-accepted.dlq")
    }

    @Test
    fun `override properties carry auto offset reset earliest (issue 686 gap)`() {
        val properties = overrideProperties()

        assertThat(properties.getProperty("$channelPrefix.auto.offset.reset"))
            .isEqualTo("earliest")
    }

    @Test
    fun `override properties expose exactly the three intended messaging keys plus config_ordinal`() {
        val properties = overrideProperties()

        assertThat(properties.stringPropertyNames()).containsExactlyInAnyOrder(
            "config_ordinal",
            "$channelPrefix.group.id",
            "$channelPrefix.dead-letter-queue.topic",
            "$channelPrefix.auto.offset.reset",
        )
    }
}
