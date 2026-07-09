// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.integration

import com.openbank.analytics.it.RedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.ListConsumerGroupsOptions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.Properties

/**
 * Guards the `mp.messaging.incoming.analytics-events-in` dotted-key YAML flattening bug (issue
 * #686): SmallRye Config's YAML source unconditionally quotes any leaf map key containing a literal
 * dot, so `group.id: analytics-sink` written as a YAML key never actually resolves — it registers
 * only as the quoted property name `"group.id"`, which `KafkaConnectorIncomingConfiguration`'s plain
 * lookup never finds. `group.id` then silently falls back to Quarkus's default
 * (`quarkus.application.name` = "openbank-analytics-sink") instead of the intended
 * "analytics-sink" — a real KafkaUser ACL grant, once this service is actually deployed, would very
 * plausibly not match that fallback (same defect class caught live for openbank-fraud-service and
 * openbank-anacredit-service).
 *
 * `application.yaml` no longer sets `group.id`/`auto.offset.reset` as YAML keys at all (fixed here);
 * this service has no gitops manifest yet (never deployed), so whoever deploys it first must set the
 * equivalent `MP_MESSAGING_INCOMING_ANALYTICS_EVENTS_IN_*` env vars (see the comment in
 * `application.yaml`). This test can't exercise env vars directly, so [RedpandaTestResource] sets the
 * equivalent property via its returned Map (a real runtime property source, not YAML) — the point of
 * this test is to assert the consumer actually joins the intended group, not the buggy fallback,
 * proving [com.openbank.analytics.application.AnalyticsConsumer]'s `@Incoming("analytics-events-in")`
 * wiring reads whatever `group.id` is configured correctly.
 *
 * Tagged `integration` per this module's convention (see `build.gradle.kts`): excluded from the
 * default offline `test` task, run explicitly with `-PwithDocker`. [RedpandaTestResource] self-skips
 * (aborts) when Docker is unavailable, mirroring the other `*IT.kt` classes in this module.
 */
@Tag("integration")
@QuarkusTest
@QuarkusTestResource(RedpandaTestResource::class)
class AnalyticsEventsConsumerGroupIdBootIT {

    @Test
    fun `consumer joins the configured analytics-sink group id, not the buggy default fallback`() {
        val bootstrap = RedpandaTestResource.lastBootstrapServers
            ?: error("RedpandaTestResource did not record its bootstrap servers")

        AdminClient.create(
            Properties().apply { put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap) },
        ).use { admin ->
            val deadline = System.currentTimeMillis() + DEADLINE_MS
            var groupIds: Set<String> = emptySet()
            while (System.currentTimeMillis() < deadline) {
                groupIds = admin.listConsumerGroups(ListConsumerGroupsOptions())
                    .all().get().map { it.groupId() }.toSet()
                if (groupIds.isNotEmpty()) break
                Thread.sleep(POLL_INTERVAL_MS)
            }
            // The regression: without the fix this set contains "openbank-analytics-sink" (the
            // Quarkus default-name fallback) instead of the intended "analytics-sink" — reproducing
            // exactly what a real Kafka broker ACL, once one exists, would reject.
            assertThat(groupIds).contains(EXPECTED_GROUP_ID)
            assertThat(groupIds).doesNotContain(BUGGY_FALLBACK_GROUP_ID)
        }
    }

    private companion object {
        const val DEADLINE_MS = 20_000L
        const val POLL_INTERVAL_MS = 1_000L
        const val EXPECTED_GROUP_ID = "analytics-sink"
        const val BUGGY_FALLBACK_GROUP_ID = "openbank-analytics-sink"
    }
}
