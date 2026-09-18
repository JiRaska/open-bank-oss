// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.context.integration

import com.openbank.context.infrastructure.AssignmentAdministrationService
import com.openbank.context.infrastructure.KybObservationReferenceConsumer
import com.openbank.context.infrastructure.KybObservationReferenceDecoder
import com.openbank.context.infrastructure.KybObservationReferenceRepository
import com.openbank.context.infrastructure.ProposeAssignmentRequest
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.coroutines.asUni
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.RecordBatch
import org.apache.kafka.common.record.TimestampType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.Metadata
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
class KybObservationReferenceIT {
    @Inject lateinit var assignments: AssignmentAdministrationService

    @Inject lateinit var repository: KybObservationReferenceRepository

    @Inject lateinit var consumer: KybObservationReferenceConsumer

    @Inject lateinit var decoder: KybObservationReferenceDecoder

    @Test
    fun `replay is idempotent and conflicting reference is rejected`() {
        val caseId = UUID.randomUUID()
        val observationId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        val reference = decoder.decode(payload(caseId, observationId), eventId.toString(), EVENT_TYPE)
        onVertx { repository.append(reference) }
        onVertx { repository.append(reference) }
        assertThatThrownBy {
            onVertx { repository.append(reference.copy(sourceSha256 = "b".repeat(64))) }
        }.hasStackTraceContaining("conflicting KYB observation reference")
        assertThat(visibleRows(eventId, scoped = true)).isEqualTo(1)
        assertThat(visibleRows(eventId, scoped = false)).isZero()
    }

    @Test
    fun `restriction hides observation before and after recorded event arrives`() {
        for (restrictionFirst in listOf(true, false)) {
            val caseId = UUID.randomUUID()
            val observationId = UUID.randomUUID()
            val recorded = decoder.decode(payload(caseId, observationId), UUID.randomUUID().toString(), EVENT_TYPE)
            val restricted = decoder.decode(
                payload(caseId, observationId, RESTRICTED_EVENT_TYPE),
                UUID.randomUUID().toString(),
                RESTRICTED_EVENT_TYPE,
            )
            if (restrictionFirst) onVertx { repository.restrict(restricted) }
            onVertx { repository.append(recorded) }
            if (!restrictionFirst) onVertx { repository.restrict(restricted) }
            onVertx { repository.restrict(restricted) }
            assertThat(onVertx { repository.history(caseId, Instant.now()) }.observations).isEmpty()
            assertThatThrownBy {
                onVertx { repository.restrict(restricted.copy(sourceSha256 = "b".repeat(64))) }
            }.hasStackTraceContaining("conflicting KYB observation restriction")
            assertThatThrownBy {
                onVertx { repository.restrict(restricted.copy(observationId = UUID.randomUUID())) }
            }.hasStackTraceContaining("conflicting KYB observation restriction")
        }
    }

    @Test
    fun `broker reference is acknowledged while mismatched case key is nacked`() {
        val caseId = UUID.randomUUID()
        val observationId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        val body = payload(caseId, observationId)
        val accepted = message(body, eventId, caseId)
        onVertx { consumer.consume(accepted.value) }
        assertThat(accepted.acked.get()).isEqualTo(1)
        assertThat(accepted.nacked.get()).isZero()
        assertThat(visibleRows(eventId, scoped = true)).isEqualTo(1)

        val rejectedId = UUID.randomUUID()
        val rejected = message(body, rejectedId, UUID.randomUUID())
        onVertx { consumer.consume(rejected.value) }
        assertThat(rejected.acked.get()).isZero()
        assertThat(rejected.nacked.get()).isEqualTo(1)
        assertThat(visibleRows(rejectedId, scoped = true)).isZero()
    }

    @Test
    fun `broker restriction is acknowledged and removes history`() {
        val caseId = UUID.randomUUID()
        val observationId = UUID.randomUUID()
        onVertx {
            repository.append(decoder.decode(payload(caseId, observationId), UUID.randomUUID().toString(), EVENT_TYPE))
        }
        val restricted = message(
            payload(caseId, observationId, RESTRICTED_EVENT_TYPE),
            UUID.randomUUID(),
            caseId,
            RESTRICTED_EVENT_TYPE,
        )
        onVertx { consumer.consume(restricted.value) }
        assertThat(restricted.acked.get()).isEqualTo(1)
        assertThat(restricted.nacked.get()).isZero()
        assertThat(onVertx { repository.history(caseId, Instant.now()) }.observations).isEmpty()
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_KYC"])
    fun `case scoped history needs an approved root and revocation removes access`() {
        val caseId = UUID.randomUUID()
        val observationId = UUID.randomUUID()
        onVertx {
            repository.append(decoder.decode(payload(caseId, observationId), UUID.randomUUID().toString(), EVENT_TYPE))
        }
        request(caseId).then().statusCode(403)
        requestAccess(caseId).then().statusCode(403)
        val proposal = onVertx {
            assignments.propose(
                ProposeAssignmentRequest(
                    ACTOR,
                    caseId.toString(),
                    PURPOSE,
                    null,
                    Instant.now().plusSeconds(3600),
                    "kyb-case:$caseId",
                ),
                "kyb-maker",
            )
        }
        val assignmentId = requireNotNull(
            onVertx { assignments.decide(proposal.id, true, "kyb-checker") }.assignmentId,
        )
        request(caseId).then().statusCode(200).header("Cache-Control", "no-store")
            .body("root", org.hamcrest.Matchers.equalTo("kyb-case:$caseId"))
            .body("observations.size()", org.hamcrest.Matchers.equalTo(1))
            .body("observations[0].observationId", org.hamcrest.Matchers.equalTo(observationId.toString()))
        requestAccess(caseId).then().statusCode(204).header("Cache-Control", "no-store")
            .header("Content-Type", org.hamcrest.Matchers.nullValue())
        given().header("X-Investigation-Case-Id", UUID.randomUUID().toString())
            .header("X-Investigation-Purpose", PURPOSE)
            .get("/api/v1/context/kyb-cases/$caseId/ownership-observations").then().statusCode(400)
        onVertx { assignments.revoke(assignmentId, "kyb-revoker") }
        request(caseId).then().statusCode(403)
        requestAccess(caseId).then().statusCode(403)
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    fun `operator cannot list KYB ownership references`() {
        request(UUID.randomUUID()).then().statusCode(403)
        requestAccess(UUID.randomUUID()).then().statusCode(403)
    }

    private fun request(caseId: UUID) = given()
        .header("X-Investigation-Case-Id", caseId.toString())
        .header("X-Investigation-Purpose", PURPOSE)
        .get("/api/v1/context/kyb-cases/$caseId/ownership-observations")

    private fun requestAccess(caseId: UUID) = given()
        .header("X-Investigation-Case-Id", caseId.toString())
        .header("X-Investigation-Purpose", PURPOSE)
        .get("/api/v1/context/kyb-cases/$caseId/access")

    private fun payload(caseId: UUID, observationId: UUID, type: String = EVENT_TYPE) =
        """{"schemaVersion":1,"eventType":"$type","caseId":"$caseId", """ +
            """"observationId":"$observationId","revision":1,"sourceSha256":"${"a".repeat(64)}"}"""

    private fun message(payload: String, eventId: UUID, brokerCaseId: UUID, type: String = EVENT_TYPE): Delivery {
        val headers = RecordHeaders()
        mapOf(
            OutboxKafkaHeaders.HEADER_EVENT_ID to eventId.toString(),
            OutboxKafkaHeaders.HEADER_IDEMPOTENCY_KEY to eventId.toString(),
            OutboxKafkaHeaders.HEADER_EVENT_TYPE to type,
        ).forEach { (name, value) -> headers.add(name, value.toByteArray(Charsets.UTF_8)) }
        val record = ConsumerRecord(
            TOPIC, 0, 0L, RecordBatch.NO_TIMESTAMP, TimestampType.NO_TIMESTAMP_TYPE,
            ConsumerRecord.NULL_SIZE, ConsumerRecord.NULL_SIZE, brokerCaseId.toString(), payload,
            headers, Optional.empty(),
        )
        val acked = AtomicInteger()
        val nacked = AtomicInteger()
        val value = Message.of(
            payload,
            Metadata.of(IncomingKafkaRecordMetadata(record, "kyb-ubo-observation-references-in")),
        )
            .withAck {
                acked.incrementAndGet()
                CompletableFuture.completedFuture(null)
            }.withNack { _: Throwable ->
                nacked.incrementAndGet()
                CompletableFuture.completedFuture(null)
            }
        return Delivery(value, acked, nacked)
    }

    @Suppress("NestedBlockDepth") // JDBC role, transaction and row scopes must close before dropping the role.
    private fun visibleRows(eventId: UUID, scoped: Boolean): Int {
        val config = ConfigProvider.getConfig()
        val role = "kyb_ref_rls_${UUID.randomUUID().toString().replace("-", "")}" // Generated SQL identifier only.
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE ROLE $role NOLOGIN NOSUPERUSER NOBYPASSRLS")
                try {
                    statement.execute("GRANT USAGE ON SCHEMA public TO $role")
                    statement.execute("GRANT SELECT ON context_kyb_observation_references TO $role")
                    statement.execute("SET ROLE $role")
                    connection.autoCommit = false
                    if (scoped) {
                        statement.execute("SELECT set_config('openbank.bank_scope', 'openbank-cz', true)")
                    }
                    connection.prepareStatement(
                        "SELECT count(*) FROM context_kyb_observation_references WHERE event_id = ?",
                    ).use { query ->
                        query.setObject(1, eventId)
                        query.executeQuery().use { rows ->
                            rows.next()
                            rows.getInt(1)
                        }
                    }
                } finally {
                    if (!connection.autoCommit) connection.rollback()
                    connection.autoCommit = true
                    statement.execute("RESET ROLE")
                    statement.execute("REVOKE SELECT ON context_kyb_observation_references FROM $role")
                    statement.execute("REVOKE USAGE ON SCHEMA public FROM $role")
                    statement.execute("DROP ROLE $role")
                }
            }
        }
    }

    private fun <T> onVertx(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private data class Delivery(val value: Message<String>, val acked: AtomicInteger, val nacked: AtomicInteger)

    private companion object {
        const val ACTOR = "kyb-history-analyst"
        const val PURPOSE = "KYB_OWNERSHIP_REVIEW"
        const val TOPIC = "openbank.kyb.ubo-observation-references"
        const val EVENT_TYPE = "KybUboObservationRecorded"
        const val RESTRICTED_EVENT_TYPE = "KybUboObservationRestricted"
    }
}
