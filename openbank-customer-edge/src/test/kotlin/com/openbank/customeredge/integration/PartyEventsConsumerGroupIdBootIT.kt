// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.integration

import com.openbank.customeredge.it.RedpandaRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.ListConsumerGroupsOptions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Properties

/**
 * Guards the `mp.messaging.incoming.party-events-in` dotted-key YAML flattening bug (issue #686):
 * SmallRye Config's YAML source unconditionally quotes any leaf map key containing a literal dot, so
 * `group.id: customer-edge-onboarding-resume` written as a YAML key never actually resolves — it
 * registers only as the quoted property name `"group.id"`, which `KafkaConnectorIncomingConfiguration`'s
 * plain lookup never finds. `group.id` then silently falls back to Quarkus's default
 * (`quarkus.application.name` = "openbank-customer-edge"), which does not match the intended
 * "customer-edge-onboarding-resume" group.
 *
 * `application.yaml` no longer sets `group.id`/`auto.offset.reset` as YAML keys at all (fixed here);
 * production sets them via `MP_MESSAGING_INCOMING_PARTY_EVENTS_IN_*` env vars
 * (`openbank-infra/gitops/components/customer-edge/customer-edge.yaml`). This test can't exercise env
 * vars directly, so [RedpandaRedisTestResource] sets the equivalent property via its returned Map (a
 * real runtime property source, not YAML) — the point of this test is to assert the consumer actually
 * joins the intended group, not the buggy fallback, proving `OnboardingResumeService`'s
 * `@Incoming("party-events-in")` wiring reads whatever `group.id` is configured correctly.
 */
@QuarkusTest
@QuarkusTestResource(RedpandaRedisTestResource::class)
class PartyEventsConsumerGroupIdBootIT {

    @Test
    fun `consumer joins the configured onboarding-resume group id, not the buggy default fallback`() {
        val bootstrap = RedpandaRedisTestResource.lastBootstrapServers
            ?: error("RedpandaRedisTestResource did not record its bootstrap servers")

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
            // The regression: without the fix this set contains "openbank-customer-edge" (the
            // Quarkus default-name fallback) instead of the intended
            // "customer-edge-onboarding-resume" — reproducing exactly what a real Kafka broker ACL
            // (once customer-edge is wired for mTLS) would reject.
            assertThat(groupIds).contains(EXPECTED_GROUP_ID)
            assertThat(groupIds).doesNotContain(BUGGY_FALLBACK_GROUP_ID)
        }
    }

    private companion object {
        const val DEADLINE_MS = 20_000L
        const val POLL_INTERVAL_MS = 1_000L
        const val EXPECTED_GROUP_ID = "customer-edge-onboarding-resume"
        const val BUGGY_FALLBACK_GROUP_ID = "openbank-customer-edge"
    }
}
