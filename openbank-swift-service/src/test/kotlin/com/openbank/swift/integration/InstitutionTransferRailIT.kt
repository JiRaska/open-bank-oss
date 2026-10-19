// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.iso20022.Iso20022ValidationResult
import com.openbank.libs.iso20022.Iso20022Validator
import com.openbank.libs.iso20022.Pacs009Builder
import com.openbank.swift.application.port.out.InstitutionTransferSubmission
import com.openbank.swift.application.port.out.InstitutionTransferVerdict
import com.openbank.swift.application.port.out.SchemeGatewayPort
import com.openbank.swift.domain.institution.InstitutionTransfer
import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.infrastructure.client.Pacs009InstructionMapper
import com.openbank.swift.institution.InstitutionTransferFixtures
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Contract 9.3 end to end inside the service: a `treasury.payment.released.v1` record on the
 * `treasury-payments-in` channel → transfer row → (stubbed) scheme → status events in
 * `swift_outbox`, asserted by plain JDBC.
 *
 * The channel runs on the in-memory connector (`%test`), which is how the record is delivered and
 * how its ack/nack is observed. The DLQ itself is the Kafka connector's `failure-strategy`, whose
 * resolved configuration `TreasuryPaymentReleasedConsumerTest` asserts against the real YAML
 * source; what this class proves is the half only a running app can — that a refused record is
 * NACKED (so the connector parks it) and not acked.
 *
 * Atomicity is measured, not inferred: each transition's row version and its outbox rows must
 * carry the same Postgres `xmin` (one transaction), as in `SwiftOutboxAtomicityIT`. The outbox
 * dispatcher is off for this class so no dispatch UPDATE rewrites an outbox row's `xmin`.
 */
@QuarkusTest
@TestProfile(InstitutionTransferRailIT.RailProfile::class)
@QuarkusTestResource(InstitutionTransferRailIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.swift.it.PostgresRedisTestResource::class)
class InstitutionTransferRailIT {

    class RailProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("openbank.outbox.dispatch-enabled" to "false")

        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(DeterministicScheme::class.java)
    }

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("swift-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    /**
     * Stands in for the clearing simulator with its own amount rule (remainder 01 → RJCT AC04,
     * otherwise ACSC), but builds and XSD-validates the REAL pacs.009 the adapter would send.
     */
    @Alternative
    @ApplicationScoped
    class DeterministicScheme : SchemeGatewayPort {
        private val validator = Iso20022Validator.forSchema(Pacs009Builder.SCHEMA_RESOURCE)

        override suspend fun submit(message: SwiftMessage) = error("MT103 path not under test")

        override suspend fun submitInstitutionTransfer(transfer: InstitutionTransfer): InstitutionTransferSubmission {
            val xml = Pacs009Builder().build(Pacs009InstructionMapper.instruction(transfer))
            check(validator.validate(xml) == Iso20022ValidationResult.Valid) { "pacs.009 failed its XSD" }
            val rejected = transfer.amount.amount.toPlainString().endsWith(".01")
            return if (rejected) {
                InstitutionTransferSubmission(InstitutionTransferVerdict.REJECTED, "AC04", xml)
            } else {
                InstitutionTransferSubmission(InstitutionTransferVerdict.SETTLED, null, xml)
            }
        }
    }

    @Inject
    @Any
    lateinit var connector: InMemoryConnector

    @Inject
    lateinit var dataSource: DataSource

    private val mapper = ObjectMapper()

    private fun release(
        amount: String = "250000.00",
        rail: String = "TARGET2",
        extra: Map<String, kotlin.Any?> = emptyMap(),
    ) = InstitutionTransferFixtures.release(
        rail = rail,
        amount = amount,
        extra = mapOf("valueDate" to LocalDate.now(ZoneOffset.UTC).toString(), "ssiVersion" to 5) + extra,
    )

    /** Sends one record and returns whether it was acked (true) or nacked (false). */
    private fun deliver(payload: String): Boolean {
        val outcome = CompletableFuture<Boolean>()
        val message = Message.of(payload)
            .withAck {
                outcome.complete(true)
                CompletableFuture.completedFuture(null)
            }
            .withNack {
                outcome.complete(false)
                CompletableFuture.completedFuture(null)
            }
        val source = connector.source<kotlin.Any>("treasury-payments-in")
        // Mirror the Kafka connector's delivery on a duplicated Vert.x context — the in-memory
        // source otherwise delivers on the root context and reactive Panache refuses it.
        source.runOnVertxContext(true)
        source.send(message)
        return outcome.get(WAIT_SECONDS, TimeUnit.SECONDS)
    }

    @Test
    fun `a release becomes one SETTLED transfer with ACCEPTED, SENT, SETTLED written atomically`() {
        val m = release()
        assertThat(deliver(InstitutionTransferFixtures.json(m))).describedAs("acked").isTrue()
        val paymentId = UUID.fromString(m["paymentId"] as String)

        val row = transferRow(paymentId)!!
        assertThat(row.status).isEqualTo("SETTLED")
        assertThat(row.rawPacs009).contains("urn:iso:std:iso:20022:tech:xsd:pacs.009.001.08", "<Cd>TGT</Cd>")
        assertThat(row.rawMt202).contains(":32A:")
        assertThat(row.ssiVersion).isEqualTo(5L)

        val events = outbox(paymentId)
        assertThat(events.map { it.status }).containsExactly("ACCEPTED", "SENT", "SETTLED")
        assertThat(events).allMatch { it.eventType == "swift.institution-transfer.status.v1" && it.synthetic }
        // ACCEPTED was committed with the insert; SENT + SETTLED with the settlement update.
        assertThat(events[1].xmin).isEqualTo(events[2].xmin).isEqualTo(row.xmin)
        assertThat(events[0].xmin).describedAs("control: an earlier transaction").isNotEqualTo(row.xmin)
        val settled = mapper.readTree(events[2].payload)
        assertThat(settled.path("idempotencyKey").asText()).isEqualTo(m["idempotencyKey"])
        assertThat(settled.path("ssiVersion").asLong()).isEqualTo(5L)
        assertThat(settled.path("messageType").asText()).isEqualTo("pacs.009")
    }

    @Test
    fun `a replay of the same release is acked and writes nothing new`() {
        val m = release(rail = "CERTIS")
        assertThat(deliver(InstitutionTransferFixtures.json(m))).isTrue()
        val paymentId = UUID.fromString(m["paymentId"] as String)
        val before = outbox(paymentId)
        assertThat(deliver(InstitutionTransferFixtures.json(m))).isTrue()
        assertThat(outbox(paymentId)).isEqualTo(before)
        assertThat(
            count(
                "SELECT count(*) FROM swift_institution_transfers WHERE idempotency_key = ?",
                m["idempotencyKey"] as String,
            ),
        )
            .isEqualTo(1)
    }

    @Test
    fun `a scheme reject ends REJECTED with the ISO reason code`() {
        val m = release(amount = "1000.01", rail = "SWIFT", extra = mapOf("currency" to "USD"))
        assertThat(deliver(InstitutionTransferFixtures.json(m))).isTrue()
        val paymentId = UUID.fromString(m["paymentId"] as String)
        assertThat(transferRow(paymentId)!!.status).isEqualTo("REJECTED")
        val events = outbox(paymentId)
        assertThat(events.map { it.status }).containsExactly("ACCEPTED", "REJECTED")
        assertThat(mapper.readTree(events[1].payload).path("reasonCode").asText()).isEqualTo("AC04")
    }

    @Test
    fun `an invalid release is nacked for the DLQ and emits REJECTED once, with no transfer row`() {
        val m = release(amount = "12.5")
        val paymentId = UUID.fromString(m["paymentId"] as String)
        repeat(2) { assertThat(deliver(InstitutionTransferFixtures.json(m))).describedAs("nacked").isFalse() }
        assertThat(transferRow(paymentId)).isNull()
        val events = outbox(paymentId)
        assertThat(events.map { it.status }).containsExactly("REJECTED")
        assertThat(mapper.readTree(events.single().payload).path("reasonCode").asText()).isEqualTo("AM12")
    }

    @Test
    fun `unparseable JSON is nacked for the DLQ`() {
        assertThat(deliver("{not json")).isFalse()
    }

    private data class Row(
        val status: String,
        val rawPacs009: String?,
        val rawMt202: String?,
        val ssiVersion: Long?,
        val xmin: String,
    )

    private data class Event(
        val status: String,
        val eventType: String,
        val synthetic: Boolean,
        val payload: String,
        val xmin: String,
    )

    private fun transferRow(paymentId: UUID): Row? = dataSource.connection.use { c ->
        c.prepareStatement(
            "SELECT status, raw_pacs009, raw_mt202, ssi_version, xmin::text FROM swift_institution_transfers WHERE payment_id = ?",
        ).use { st ->
            st.setObject(1, paymentId)
            st.executeQuery().use { r ->
                if (r.next()) {
                    Row(
                        r.getString(1),
                        r.getString(2),
                        r.getString(3),
                        r.getObject(4) as Long?,
                        r.getString(5),
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun outbox(paymentId: UUID): List<Event> = dataSource.connection.use { c ->
        c.prepareStatement(
            "SELECT event_type, synthetic, payload, xmin::text FROM swift_outbox WHERE aggregate_id = ? ORDER BY created_at, id",
        ).use { st ->
            st.setObject(1, paymentId)
            st.executeQuery().use { r ->
                generateSequence { if (r.next()) r else null }.map {
                    val payload = it.getString(3)
                    Event(
                        mapper.readTree(payload).path("status").asText(),
                        it.getString(1),
                        it.getBoolean(2),
                        payload,
                        it.getString(4),
                    )
                }.toList()
            }
        }
    }

    private fun count(sql: String, arg: String): Int = dataSource.connection.use { c ->
        c.prepareStatement(sql).use { st ->
            st.setString(1, arg)
            st.executeQuery().use { r ->
                r.next()
                r.getInt(1)
            }
        }
    }

    private companion object {
        const val WAIT_SECONDS = 30L
    }
}
