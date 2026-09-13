// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.it

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName

/**
 * Isolated Redpanda (Kafka API) + Valkey (Redis) per test JVM via Testcontainers (no Postgres —
 * customer-edge has no database of its own). customer-edge consumes Kafka
 * (`OnboardingResumeService.onPartyEvent`, `@Incoming("party-events-in")`) so a real broker is
 * needed to exercise the fix for issue #686; Redis backs the pending-onboarding store
 * (`PendingOnboardingStore`, ADR-0072).
 *
 * Also sets `mp.messaging.incoming.party-events-in.group.id` here — a genuine runtime property
 * source, not YAML — for the same reason production sets it via env vars (issue #686): SmallRye
 * Config's YAML source unconditionally quotes any leaf map key containing a literal dot, so a
 * `group.id` written as a YAML key in `application.yaml` never resolves. This returned Map does not
 * go through that flattener, so it's the one place in the test tree that can genuinely exercise the
 * fixed group id end-to-end (see
 * [com.openbank.customeredge.integration.PartyEventsConsumerGroupIdBootIT]).
 */
class RedpandaRedisTestResource : QuarkusTestResourceLifecycleManager {

    private var redpanda: RedpandaContainer? = null
    private var redis: GenericContainer<*>? = null

    override fun start(): Map<String, String> {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            throw TestAbortedException("Docker not available — skipping Testcontainers IT")
        }
        val rp = RedpandaContainer(
            DockerImageName.parse(REDPANDA_IMAGE)
                .asCompatibleSubstituteFor("docker.redpanda.com/redpandadata/redpanda"),
        )
        rp.start()
        redpanda = rp
        lastBootstrapServers = rp.bootstrapServers
        TestInfrastructureEvidence.record("redpanda", REDPANDA_IMAGE, "started")

        val rd = GenericContainer(DockerImageName.parse(VALKEY_IMAGE)).withExposedPorts(
            REDIS_PORT,
        )
        rd.start()
        redis = rd
        TestInfrastructureEvidence.record("valkey", VALKEY_IMAGE, "started")

        return mapOf(
            "kafka.bootstrap.servers" to rp.bootstrapServers,
            "mp.messaging.connector.smallrye-kafka.bootstrap.servers" to rp.bootstrapServers,
            "mp.messaging.incoming.party-events-in.group.id" to EXPECTED_GROUP_ID,
            "quarkus.redis.hosts" to "redis://${rd.host}:${rd.getMappedPort(REDIS_PORT)}",
            "openbank.edge.identity-resume-enabled" to "true",
            // Both required (no defaultValue in production code) and normally supplied via
            // ExternalSecrets-backed env vars (customer-edge.yaml); this test never calls the
            // upstream services or the Keycloak Admin API those secrets guard, so any non-empty
            // placeholder satisfies Quarkus's config-property presence check at boot.
            "openbank.upstream.client-secret" to "test-upstream-secret",
            "openbank.edge.keycloak-admin-client-secret" to "test-keycloak-admin-secret",
            // Same "required, no real defaultValue" story as the two secrets above (ADR-0066 F2
            // WebAuthn RP — EnrollmentTicketService, WebAuthnKeycloakClient); this test never
            // exercises either.
            "openbank.webauthn.enrollment-ticket-secret" to "test-enrollment-ticket-secret",
            "openbank.webauthn.kc-client-secret" to "test-webauthn-kc-client-secret",
            "quarkus.devservices.enabled" to "false",
        )
    }

    override fun stop() {
        try {
            redpanda?.let {
                it.stop()
                TestInfrastructureEvidence.record("redpanda", REDPANDA_IMAGE, "stopped")
            }
        } finally {
            redis?.let {
                it.stop()
                TestInfrastructureEvidence.record("valkey", VALKEY_IMAGE, "stopped")
            }
        }
    }

    companion object {
        private const val REDPANDA_IMAGE = "redpandadata/redpanda:v24.1.2"
        private const val VALKEY_IMAGE = "docker.io/valkey/valkey:7.2-alpine"
        private const val REDIS_PORT = 6379

        /** Matches [com.openbank.customeredge.integration.PartyEventsConsumerGroupIdBootIT]'s expectation. */
        const val EXPECTED_GROUP_ID = "customer-edge-onboarding-resume"

        /**
         * Set by [start] and read by test classes that need to talk to the broker directly (e.g.
         * [com.openbank.customeredge.integration.PartyEventsConsumerGroupIdBootIT]'s `AdminClient`). A
         * static holder is simplest here since [QuarkusTestResourceLifecycleManager] instances aren't
         * injectable.
         */
        @Volatile
        var lastBootstrapServers: String? = null
            private set
    }
}
