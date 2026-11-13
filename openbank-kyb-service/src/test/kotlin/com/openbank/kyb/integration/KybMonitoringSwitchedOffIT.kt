// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.integration

import com.openbank.libs.testing.containers.PostgresRedpandaTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import org.apache.kafka.clients.admin.Admin
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The one switch, off — the state this change ships in — against a REAL broker.
 *
 * With `openbank.kyb.monitoring.enabled=false` the finding channel is not opened at all: the
 * service boots, is ready, and never joins a consumer group on the screening topic. That is what
 * makes switch-on order-free for the consumer side — there is no committed offset from the time
 * before the switch, so nothing produced while it was off is silently skipped or silently applied.
 *
 * The control is the other incoming channel of the same application: its group IS on the broker,
 * so a probe that could not see consumer groups would fail there rather than pass here.
 */
@QuarkusTest
@TestProfile(KybMonitoringSwitchedOffIT.Profile::class)
class KybMonitoringSwitchedOffIT {

    class Profile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true

        override fun testResources() =
            listOf(QuarkusTestProfile.TestResourceEntry(PostgresRedpandaTestResource::class.java))

        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.kyb.monitoring.enabled" to "false",
            "mp.messaging.incoming.party-events-in.group.id" to "kyb-monitoring-off-it-party",
        )
    }

    @ConfigProperty(name = "kafka.bootstrap.servers")
    lateinit var bootstrap: String

    @Test
    fun `with the switch off the service is ready and the finding channel is not opened`() {
        given().get("/q/health/ready").then().statusCode(200)

        val deadline = System.nanoTime() + Duration.ofSeconds(AWAIT_SECONDS).toNanos()
        var groups = groups()
        while (PARTY_GROUP !in groups && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MS)
            groups = groups()
        }

        assertThat(
            groups,
        ).describedAs("control: the channel that is always on has joined its group").contains(PARTY_GROUP)
        assertThat(groups).describedAs("the finding channel follows the switch").doesNotContain(MONITORING_GROUP)
    }

    /** Groups that have a member right now: a group left behind by another test's application is not this one's. */
    private fun groups(): Set<String> =
        Admin.create(mapOf<String, Any>("bootstrap.servers" to bootstrap)).use { admin ->
            val ids = admin.listConsumerGroups().all().get(ADMIN_SECONDS, TimeUnit.SECONDS).map { it.groupId() }
            admin.describeConsumerGroups(ids).all().get(ADMIN_SECONDS, TimeUnit.SECONDS)
                .filterValues { it.members().isNotEmpty() }.keys
        }

    private companion object {
        const val MONITORING_GROUP = "kyb-service-sanctions-monitoring"
        const val PARTY_GROUP = "kyb-monitoring-off-it-party"
        const val AWAIT_SECONDS = 60L
        const val ADMIN_SECONDS = 20L
        const val POLL_MS = 250L
    }
}
