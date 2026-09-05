// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.smallrye.reactive.messaging.memory.InMemorySource
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * The end-to-end proof #6248 asks for: a real `DEVICE_ENROLLED` message, delivered through the
 * real `@Incoming` handler, landing as a real row read back over plain JDBC.
 *
 * Why not a unit test: every unit test on both sides of this defect was green for the whole time
 * it was live. The producer's tests asserted the payload it built, the consumer's asserted the
 * parse and the metric, and the projection's asserted the record it handed a **mocked**
 * repository. None of them could see that no row ever changed — the only observable that was ever
 * wrong. A mocked repository cannot establish that a row landed.
 *
 * The payloads below are the real 2026-08-19 sandbox message shape (`sca_outbox` id 2101), minus
 * the key material.
 */
@QuarkusTest
@QuarkusTestResource(ScaEnrolmentProjectionIT.InMemoryScaChannels::class)
@QuarkusTestResource(com.openbank.onboarding.it.OnboardingPostgresTestResource::class)
class ScaEnrolmentProjectionIT {

    class InMemoryScaChannels : io.quarkus.test.common.QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchIncomingChannelsToInMemory("sca-events-in") +
                InMemoryConnector.switchIncomingChannelsToInMemory("party-events-in") +
                InMemoryConnector.switchIncomingChannelsToInMemory("kyc-events-in")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    @Any
    lateinit var connector: InMemoryConnector

    /**
     * The measured defect, end to end: an enrolment for a party whose row does not exist yet.
     *
     * On `origin/main` before this change the projection returned without writing, no row ever
     * appeared, and the outcome was counted as a skip that nothing replays — so this assertion
     * fails on `no row for party <id>`.
     */
    @Test
    fun `a DEVICE_ENROLLED for an unknown party is projected, not dropped`() {
        val partyId = UUID.randomUUID()
        val before = Instant.now()

        sendSca(partyId, credentialId = "cred-1")

        val row = readRecord(partyId)
        assertThat(row).describedAs("no row for party %s", partyId).isNotNull
        assertThat(row!!.scaEnrolled).isTrue()
        assertThat(row.deviceCount).isEqualTo(1)
        // Recency, never non-nullity: `updated_at` carries the event time, and an Instant.EPOCH
        // or a default-constructed value would satisfy isNotNull just as well.
        assertThat(row.updatedAt).isBetween(before.minusSeconds(60), Instant.now().plusSeconds(60))
    }

    /**
     * Idempotence, which is the precondition for backfilling the enrolments already lost: the
     * originals are long past Kafka's retention, so any recovery re-publishes them, and a
     * `deviceCount + 1` projection would inflate the count on every run.
     */
    @Test
    fun `replaying the same enrolment does not double-count, and a second device does`() {
        val partyId = UUID.randomUUID()

        sendSca(partyId, credentialId = "cred-a")
        sendSca(partyId, credentialId = "cred-a")
        sendSca(partyId, credentialId = "cred-a")

        assertThat(readRecord(partyId)!!.deviceCount)
            .describedAs("three deliveries of one credential are one device")
            .isEqualTo(1)

        sendSca(partyId, credentialId = "cred-b")

        assertThat(readRecord(partyId)!!.deviceCount)
            .describedAs("a genuinely different credential still counts")
            .isEqualTo(2)
    }

    /**
     * The other half of the ordering race. `PARTY_CREATED` arriving *after* the enrolment must
     * fill in identity without resetting progress — the old branch wrote a fixed
     * `sca_enrolled = false, device_count = 0` over whatever the row held, which would have
     * turned the seeded row straight back into the defect.
     */
    @Test
    fun `a late PARTY_CREATED fills in identity without wiping the enrolment`() {
        val partyId = UUID.randomUUID()

        sendSca(partyId, credentialId = "cred-late")
        sendParty(partyId)

        val row = readRecord(partyId)!!
        assertThat(row.legalName).isEqualTo("Jana Nova")
        assertThat(row.scaEnrolled).describedAs("PARTY_CREATED must not reset SCA state").isTrue()
        assertThat(row.deviceCount).isEqualTo(1)
    }

    // ── Driving the real channel ─────────────────────────────────────────────

    private fun sendSca(partyId: UUID, credentialId: String) = send(
        "sca-events-in",
        """{"eventType":"DEVICE_ENROLLED","deviceId":"${UUID.randomUUID()}","partyId":"$partyId",""" +
            """"credentialId":"$credentialId","algorithm":"ES256",""" +
            """"occurredAt":"${Instant.now()}","sourceService":"sca-service"}""",
    )

    private fun sendParty(partyId: UUID) = send(
        "party-events-in",
        """{"eventType":"PARTY_CREATED","partyId":"$partyId","legalName":"Jana Nova",""" +
            """"email":"jana@example.test","occurredAt":"${Instant.now()}"}""",
    )

    /**
     * `runOnVertxContext(true)` is load-bearing: a `suspend @Incoming` handler needs the
     * duplicated context the real Kafka connector supplies, or the reactive Panache transaction
     * inside the projection fails with `No current Vertx context found`.
     *
     * Waiting on the ack rather than polling is what makes the assertion deterministic — the ack
     * completes only after the handler has run to completion.
     */
    private fun send(channel: String, payload: String) {
        val source: InMemorySource<Message<String>> = connector.source(channel)
        source.runOnVertxContext(true)
        val acked = CompletableFuture<Void>()
        source.send(
            Message.of(
                payload,
                Supplier<CompletionStage<Void>> {
                    acked.complete(null)
                    CompletableFuture.completedFuture(null)
                },
            ),
        )
        acked.get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    // ── Reading it back the way a different pod would ────────────────────────

    private data class Row(
        val scaEnrolled: Boolean,
        val deviceCount: Int,
        val updatedAt: Instant,
        val legalName: String?,
    )

    private fun readRecord(partyId: UUID): Row? = jdbc().use { conn -> conn.selectRow(partyId) }

    private fun Connection.selectRow(partyId: UUID): Row? = prepareStatement(SELECT_ROW).use { st ->
        st.setObject(1, partyId)
        st.executeQuery().use { rs -> if (rs.next()) rs.toRow() else null }
    }

    private fun java.sql.ResultSet.toRow() = Row(
        scaEnrolled = getBoolean(1),
        deviceCount = getInt(2),
        updatedAt = getTimestamp(3).toInstant(),
        legalName = getString(4),
    )

    private fun jdbc(): Connection {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }

    private companion object {
        const val ACK_TIMEOUT_SECONDS = 30L
        const val SELECT_ROW =
            "SELECT sca_enrolled, device_count, updated_at, legal_name FROM onboarding_records WHERE party_id = ?"
    }
}
