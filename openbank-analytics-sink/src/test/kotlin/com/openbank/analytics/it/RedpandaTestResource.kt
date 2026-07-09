// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.it

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName

/**
 * Isolated Redpanda (Kafka API) broker per test JVM via Testcontainers, for booting the real
 * `@Incoming("analytics-events-in")` consumer (issue #686). This service owns no OLTP database
 * (ADR-0022 — see `application.yaml`'s "NOTE: no datasource" comment) and the default
 * `LoggingAnalyticsSink` / `LoggingDeadLetterSink` bindings need no external system, so a broker is
 * the only piece of infra a real boot needs here.
 */
class RedpandaTestResource : QuarkusTestResourceLifecycleManager {

    private var redpanda: RedpandaContainer? = null

    override fun start(): Map<String, String> {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            throw TestAbortedException("Docker not available — skipping Testcontainers IT")
        }
        val rp = RedpandaContainer(
            DockerImageName.parse("redpandadata/redpanda:v24.1.2")
                .asCompatibleSubstituteFor("docker.redpanda.com/redpandadata/redpanda"),
        )
        rp.start()
        redpanda = rp
        lastBootstrapServers = rp.bootstrapServers

        return mapOf(
            "kafka.bootstrap.servers" to rp.bootstrapServers,
            "mp.messaging.connector.smallrye-kafka.bootstrap.servers" to rp.bootstrapServers,
            // Set here — a genuine runtime property source, not YAML — for the same reason
            // production sets it via env vars (issue #686): SmallRye Config's YAML source
            // unconditionally quotes any leaf map key containing a literal dot, so a `group.id`
            // written as a YAML key in application.yaml never resolves. A
            // QuarkusTestResourceLifecycleManager's returned Map does not go through that
            // flattener, so this is the one place in the test tree that can genuinely exercise the
            // fixed group id end-to-end.
            "mp.messaging.incoming.analytics-events-in.group.id" to "analytics-sink",
            "mp.messaging.incoming.analytics-events-in.auto.offset.reset" to "earliest",
            "quarkus.devservices.enabled" to "false",
            // Unrelated pre-existing defect surfaced by this being the service's first-ever real
            // @QuarkusTest boot (issue #686 is scoped to the Kafka group.id bug only, so this is
            // worked around here rather than fixed): openbank.analytics.schema.known resolves via
            // ${ANALYTICS_SCHEMA_KNOWN:} to a literal empty string when unset, and
            // ConfigSchemaCatalogSource declares it as plain `String` (not `Optional<String>`).
            // SmallRye's ConfigRecorder.validateConfigProperties treats an empty-string resolution
            // as "no value" for the built-in String converter and throws SRCFG00040 eagerly at
            // startup — the exact class of bug CLAUDE.md's "@ConfigProperty optional field must be
            // Optional<String>" pitfall describes. Give it a non-empty value here so this IT can
            // actually boot; see the flagged follow-up in the PR description.
            "openbank.analytics.schema.known" to "unused.placeholder:1",
        )
    }

    override fun stop() {
        redpanda?.stop()
    }

    companion object {
        /**
         * Set by [start] and read by test classes that need to talk to the broker directly (e.g.
         * [com.openbank.analytics.integration.AnalyticsEventsConsumerGroupIdBootIT]'s `AdminClient`).
         * A static holder is simplest here since [QuarkusTestResourceLifecycleManager] instances
         * aren't injectable.
         */
        @Volatile
        var lastBootstrapServers: String? = null
            private set
    }
}
