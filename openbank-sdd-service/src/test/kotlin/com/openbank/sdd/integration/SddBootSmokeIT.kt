// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sdd.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Boot smoke test guarding the "released-but-never-booted" defect class.
 *
 * sdd-service is a released component (version.txt) with no GitOps deployment. The same defect class
 * bit psd2-service (#1163 missing runtime DB extensions, #1170 duplicate YAML key dropping the HTTP
 * port). This IT boots the full app on a Testcontainers Postgres (reusing the PostgresTestResource
 * already used by SddOutboxDispatchIT) with the single outgoing Kafka channel swapped to the in-memory
 * connector, then asserts the readiness probe is UP and the service-info endpoint answers — the two
 * signals that prove the wiring, config and Flyway migrations survive a real boot.
 */
@QuarkusTest
@QuarkusTestResource(SddBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.sdd.it.PostgresTestResource::class)
class SddBootSmokeIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            val props = InMemoryConnector.switchOutgoingChannelsToInMemory("sdd-events-out").toMutableMap()
            props["quarkus.kafka.devservices.enabled"] = "false"
            return props
        }

        override fun stop() = InMemoryConnector.clear()
    }

    @Test
    fun `the app boots and the readiness probe reports UP`() {
        Given { this } When { get("/q/health/ready") } Then { statusCode(200) }
    }

    @Test
    fun `the service-info endpoint answers on the configured HTTP port`() {
        val body = (Given { this } When { get("/api/v1/info") } Then { statusCode(200) })
            .extract().body().asString()
        assertThat(body).contains("openbank-sdd-service")
    }
}
