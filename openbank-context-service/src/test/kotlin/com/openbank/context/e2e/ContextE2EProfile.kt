// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.e2e

import com.openbank.libs.testing.containers.PostgresRedpandaTestResource
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry

/**
 * Shared profile for the context E2E suite: real Postgres + real Redpanda (the shared
 * [PostgresRedpandaTestResource]) and a WireMock `aml-service` stand-in for the source-status
 * check, so both the inbound evidence wires (`openbank.aml.events`, `openbank.delegation.events`)
 * and the outbound audit-commitment wire (`openbank.context.audit.commitments`) are the real
 * Kafka transport — no existing test exercises any of them, every IT switches all channels to the
 * in-memory connector. The scheduled commitment relay stays enabled so an allowed read becomes a
 * wire fact, not just a JDBC row.
 *
 * Every E2E class must use exactly this profile class so Quarkus reuses one test JVM (and one
 * container set) across the whole suite.
 */
class ContextE2EProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources() = true

    override fun testResources() = listOf(
        TestResourceEntry(
            PostgresRedpandaTestResource::class.java,
            mapOf("db" to "openbank_context_e2e"),
        ),
        TestResourceEntry(ContextE2EWireMockResource::class.java),
    )

    override fun getConfigOverrides() = mapOf(
        "authz.enforce" to "false",
        // application.yaml's %test block pins every channel to smallrye-in-memory; put the E2E
        // chains back on the real broker. DLQ wiring and topics come from the main config.
        "mp.messaging.incoming.aml-case-evidence-in.connector" to "smallrye-kafka",
        "mp.messaging.incoming.delegation-history-in.connector" to "smallrye-kafka",
        "mp.messaging.outgoing.context-audit-commitments-out.connector" to "smallrye-kafka",
        // The commitment relay only dispatches when export is on; its schedule (5 s, 10 s
        // initial delay) makes every allowed read a Kafka wire fact within the test horizon.
        "openbank.context.audit-export.enabled" to "true",
    )
}
