// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.BeforeAll
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Shared helpers for the context E2E suite: events published to the real Redpanda topics with the
 * exact broker contract the consumers enforce (record key == case id, `ce-id` == `idempotency-key`
 * headers, `ce-type` event type), wire facts drained back from the broker (audit commitments, DLQ
 * rejects), and durable evidence read over JDBC under the bank-scope RLS the migrations install.
 *
 * Concrete classes carry `@QuarkusTest` + `@TestProfile(ContextE2EProfile::class)` — the
 * annotations do not inherit, and every E2E class must name the same profile so Quarkus reuses
 * one test JVM (and one container set) across the whole suite.
 */
abstract class ContextE2EBase {

    private val mapper = ObjectMapper()

    // --------------------------------------------------------------------------- Kafka inbound

    /** Publishes an `aml.case.created.v1` event with a broker contract the consumer accepts. */
    protected fun publishAmlCaseCreated(
        caseId: UUID,
        partyId: UUID,
        accountId: UUID? = null,
        risk: String = "MEDIUM",
        status: String = "OPEN",
        occurredAt: Instant = Instant.now(),
    ) {
        val account = accountId?.let { "\"$it\"" } ?: "null"
        val payload =
            """{"caseId":"$caseId","partyId":"$partyId","accountId":$account,
               "status":"$status","riskLevel":"$risk","screeningType":"TRANSACTION_MONITORING",
               "occurredAt":"$occurredAt"}""".replace(Regex("\\s+"), "")
        publishAml(caseId, payload, "aml.case.created.v1")
    }

    /** Publishes an `aml.case.status_changed.v1` event for an existing case. */
    protected fun publishAmlStatusChange(
        caseId: UUID,
        previousStatus: String,
        newStatus: String,
        occurredAt: Instant = Instant.now(),
    ) {
        val payload =
            """{"caseId":"$caseId","previousStatus":"$previousStatus","newStatus":"$newStatus","occurredAt":"$occurredAt"}"""
        publishAml(caseId, payload, "aml.case.status_changed.v1")
    }

    private fun publishAml(caseId: UUID, payload: String, type: String, keyOverride: String? = null) {
        val eventId = UUID.randomUUID().toString()
        val headers = RecordHeaders().apply {
            add("ce-id", eventId.toByteArray(Charsets.UTF_8))
            add("idempotency-key", eventId.toByteArray(Charsets.UTF_8))
            add("ce-type", type.toByteArray(Charsets.UTF_8))
        }
        kafkaProducer().use { producer ->
            producer.send(
                ProducerRecord<String, String>(AML_TOPIC, null, keyOverride ?: caseId.toString(), payload, headers),
            )
                .get(10, TimeUnit.SECONDS)
        }
    }

    /** Publishes a raw record whose broker contract is broken on purpose ([keyOverride]). */
    protected fun publishAmlMalformed(caseId: UUID) {
        publishAml(
            caseId,
            """{"caseId":"$caseId"}""",
            "aml.case.created.v1",
            keyOverride = UUID.randomUUID().toString(),
        )
    }

    /** Publishes a delegation lifecycle event consumed by `delegation-history-in` (plain payload). */
    protected fun publishDelegationEvent(payload: String) {
        kafkaProducer().use { producer ->
            producer.send(ProducerRecord<String, String>(DELEGATION_TOPIC, payload)).get(10, TimeUnit.SECONDS)
        }
    }

    protected fun delegationActivated(
        delegationId: UUID,
        revision: Long,
        grantor: UUID = UUID.randomUUID(),
        grantee: UUID = UUID.randomUUID(),
        occurredAt: Instant = Instant.now(),
        eventType: String = "DelegationActivated",
        capabilities: String = """["READ"]""",
        resourceType: String = "ACCOUNT",
    ): String = """
        {"eventType":"$eventType","lifecycleRevision":$revision,"aggregateId":"$delegationId",
         "grantorPartyId":"$grantor","granteePartyId":"$grantee","resourceType":"$resourceType",
         "resourceId":"${UUID.randomUUID()}","capabilities":$capabilities,
         "validFrom":"${occurredAt.minusSeconds(3600)}","occurredAt":"$occurredAt"}
    """.trimIndent()

    // -------------------------------------------------------------------------- Kafka outbound

    /**
     * Drains `openbank.context.audit.commitments` until the commitment for read audit
     * [auditId] appears. The wire envelope deliberately carries no row fields — the commitment
     * is a SHA-256 of the restricted row — so the aggregate id is the only join key.
     */
    protected fun awaitAuditCommitment(auditId: UUID): JsonNode = drainTopic(
        group = "context-e2e-commitments",
        topic = COMMITMENTS_TOPIC,
        timeout = Duration.ofSeconds(60),
    ) { record ->
        val node = mapper.readTree(record.value())
        if (node.get("eventType")?.asText() == "CONTEXT_READ_AUDIT_COMMITTED" &&
            node.get("aggregateId")?.asText() == auditId.toString()
        ) {
            node
        } else {
            null
        }
    } ?: error("no CONTEXT_READ_AUDIT_COMMITTED for audit $auditId within 60s")

    /** Drains a DLQ topic until [predicate] matches; returns the rejected record. */
    protected fun awaitDlqRecord(
        predicate: (ConsumerRecord<String, String>) -> Boolean,
    ): ConsumerRecord<String, String> = drainTopic(
        group = "context-e2e-dlq-${UUID.randomUUID()}",
        topic = AML_DLQ_TOPIC,
        timeout = Duration.ofSeconds(30),
    ) { record -> if (predicate(record)) record else null }
        ?: error("no matching DLQ record within 30s")

    /** Polls [topic] from the beginning until [matcher] returns a value or [timeout] elapses. */
    private fun <T> drainTopic(
        group: String,
        topic: String,
        timeout: Duration,
        matcher: (ConsumerRecord<String, String>) -> T?,
    ): T? {
        val bootstrap = ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java)
        KafkaConsumer<String, String>(
            mapOf(
                "bootstrap.servers" to bootstrap,
                "group.id" to group,
                "auto.offset.reset" to "earliest",
                "key.deserializer" to StringDeserializer::class.qualifiedName,
                "value.deserializer" to StringDeserializer::class.qualifiedName,
                "enable.auto.commit" to "false",
            ),
        ).use { consumer ->
            consumer.subscribe(listOf(topic))
            val deadline = System.nanoTime() + timeout.toNanos()
            while (System.nanoTime() < deadline) {
                val found = scan(consumer.poll(Duration.ofMillis(500)), matcher)
                if (found != null) return found
            }
        }
        return null
    }

    private fun <T> scan(
        records: ConsumerRecords<String, String>,
        matcher: (ConsumerRecord<String, String>) -> T?,
    ): T? {
        for (record in records) {
            val matched = matcher(record)
            if (matched != null) return matched
        }
        return null
    }

    private fun kafkaProducer() = KafkaProducer<String, String>(
        mapOf(
            "bootstrap.servers" to ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java),
            "key.serializer" to StringSerializer::class.qualifiedName,
            "value.serializer" to StringSerializer::class.qualifiedName,
            "acks" to "all",
        ),
    )

    // --------------------------------------------------------------------------------- JDBC

    /** Runs [block] on a JDBC connection scoped to the bank the service writes as (RLS). */
    protected fun <T> jdbc(block: (Connection) -> T): T = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.username", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.password", String::class.java),
    ).use { connection ->
        connection.autoCommit = false
        connection.prepareStatement("SELECT set_config('openbank.bank_scope', ?, true)").use { ps ->
            ps.setString(1, BANK_SCOPE)
            ps.executeQuery().close()
        }
        val result = block(connection)
        connection.commit()
        result
    }

    protected fun awaitAmlEvidenceRows(caseId: UUID, minimum: Int): Int {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            val rows = jdbc { c ->
                c.prepareStatement(
                    "SELECT count(*) FROM context_aml_case_evidence WHERE case_id = ?",
                ).use { ps ->
                    ps.setObject(1, caseId)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }
            if (rows >= minimum) return rows
            Thread.sleep(250)
        }
        error("AML evidence for $caseId did not reach $minimum rows within 30s")
    }

    protected fun awaitAuthorityRows(delegationId: UUID, minimum: Int): Int {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            val rows = jdbc { c ->
                c.prepareStatement(
                    "SELECT count(*) FROM context_authority_history WHERE delegation_id = ?",
                ).use { ps ->
                    ps.setObject(1, delegationId)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }
            if (rows >= minimum) return rows
            Thread.sleep(250)
        }
        error("authority history for $delegationId did not reach $minimum rows within 30s")
    }

    /** Seeds an active case assignment, the same durable row the REST journey produces. */
    protected fun seedAssignment(
        principal: String,
        caseId: String,
        purpose: String,
        rootRef: String,
        validFrom: Instant = Instant.now().minusSeconds(3600),
        validTo: Instant = Instant.now().plusSeconds(3600),
    ) {
        jdbc { c ->
            c.prepareStatement(
                """INSERT INTO context_case_assignments
                   (assignment_id, bank_scope, principal_id, case_id, purpose, root_ref, valid_from, valid_to, created_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT (bank_scope, principal_id, case_id, purpose, valid_from) DO NOTHING
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, UUID.randomUUID())
                ps.setString(2, BANK_SCOPE)
                ps.setString(3, principal)
                ps.setString(4, caseId)
                ps.setString(5, purpose)
                ps.setString(6, rootRef)
                ps.setTimestamp(7, Timestamp.from(validFrom))
                ps.setTimestamp(8, Timestamp.from(validTo))
                ps.setTimestamp(9, Timestamp.from(Instant.now()))
                ps.executeUpdate()
            }
        }
    }

    protected fun readAuditDecisions(caseId: String): List<String> = jdbc { c ->
        c.prepareStatement(
            "SELECT decision, reason_code FROM context_read_audit WHERE case_id = ? ORDER BY occurred_at",
        ).use { ps ->
            ps.setString(1, caseId)
            ps.executeQuery().use { rs ->
                generateSequence { if (rs.next()) "${rs.getString(1)}:${rs.getString(2)}" else null }.toList()
            }
        }
    }

    protected fun readAuditId(caseId: String, decision: String = "ALLOWED"): UUID = jdbc { c ->
        c.prepareStatement(
            "SELECT audit_id FROM context_read_audit WHERE case_id = ? AND decision = ? " +
                "ORDER BY occurred_at DESC LIMIT 1",
        ).use { ps ->
            ps.setString(1, caseId)
            ps.setString(2, decision)
            ps.executeQuery().use { rs ->
                assertThat(rs.next()).isTrue()
                rs.getObject(1, UUID::class.java)
            }
        }
    }

    protected fun commitmentStatus(auditId: UUID): String? = jdbc { c ->
        c.prepareStatement(
            "SELECT status FROM context_audit_commitment_outbox WHERE audit_id = ?",
        ).use { ps ->
            ps.setObject(1, auditId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    protected companion object {
        const val BANK_SCOPE = "openbank-cz"
        const val AML_TOPIC = "openbank.aml.events"
        const val AML_DLQ_TOPIC = "openbank.dlq.context-service.aml-case-evidence"
        const val DELEGATION_TOPIC = "openbank.delegation.events"
        const val COMMITMENTS_TOPIC = "openbank.context.audit.commitments"
        const val INVESTIGATOR = "ctx-investigator@openbank.test"
        const val AML_PURPOSE = "AML_INVESTIGATION"
        const val AUTHORITY_PURPOSE = "AUTHORIZATION_REVIEW"

        @JvmStatic
        @Synchronized
        @BeforeAll
        protected fun ensureTopicsOnce() {
            if (topicsEnsured) return
            val bootstrap = ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java)
            Admin.create(mapOf("bootstrap.servers" to bootstrap)).use { admin ->
                val existing = admin.listTopics().names().get(20, TimeUnit.SECONDS)
                val wanted = listOf(
                    AML_TOPIC,
                    AML_DLQ_TOPIC,
                    DELEGATION_TOPIC,
                    COMMITMENTS_TOPIC,
                    "openbank.dlq.context-service.delegation-history",
                )
                val toCreate = wanted.filter { it !in existing }.map { NewTopic(it, 1, 1) }
                if (toCreate.isNotEmpty()) {
                    admin.createTopics(toCreate).all().get(20, TimeUnit.SECONDS)
                }
            }
            topicsEnsured = true
        }

        private var topicsEnsured = false
    }
}
