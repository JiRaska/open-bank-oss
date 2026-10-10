// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sepainstant.integration

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.sepainstant.infrastructure.outbox.SctInstOutboxDispatcher
import com.openbank.sepainstant.infrastructure.persistence.repository.SctInstOutboxRepositoryImpl
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/** A broker failure after HTTP/DB commit must leave a retryable event, then mark it SENT. */
@QuarkusTest
@TestProfile(SctInstOutboxRetryIT.NoSchemeProfile::class)
@QuarkusTestResource(
    value = com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_sepa_instant_retry_it")],
    restrictToAnnotatedClass = true,
)
class SctInstOutboxRetryIT {
    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var repository: SctInstOutboxRepositoryImpl

    class NoSchemeProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.sct-inst.scheme-submission.enabled" to "false",
            "openbank.outbox.dispatch-enabled" to "false",
        )
    }

    private data class OutboxRow(val eventId: UUID, val status: String, val attempts: Int, val sentAt: OffsetDateTime?)

    private fun rowFor(paymentId: UUID): OutboxRow = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT event_id, status, attempt_count, sent_at FROM sct_inst_outbox WHERE aggregate_id = ?",
        ).use { query ->
            query.setObject(1, paymentId)
            query.executeQuery().use { rows ->
                assertThat(rows.next()).isTrue()
                val row = OutboxRow(
                    rows.getObject(1, UUID::class.java),
                    rows.getString(2),
                    rows.getInt(3),
                    rows.getObject(4, OffsetDateTime::class.java),
                )
                assertThat(rows.next()).isFalse()
                row
            }
        }
    }

    private fun paymentStatus(paymentId: UUID): String = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT status FROM sct_inst_payments WHERE payment_id = ?").use { query ->
            query.setObject(1, paymentId)
            query.executeQuery().use { rows ->
                assertThat(rows.next()).isTrue()
                rows.getString(1)
            }
        }
    }

    private fun tick(dispatcher: SctInstOutboxDispatcher) {
        VertxContextSupport.subscribeAndAwait {
            uni(CoroutineScope(Dispatchers.Unconfined)) { dispatcher.dispatch() }
        }
    }

    @Test
    @TestSecurity(user = "operator-outbox-retry", roles = ["ROLE_OPERATOR"])
    fun `broker failure after payment commit remains retryable until a later successful send`() {
        val key = UUID.randomUUID().toString()
        val paymentId = UUID.fromString(
            given()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", key)
                .body(
                    """{"idempotencyKey":"$key","debtorAccountId":"${UUID.randomUUID()}","debtorIban":"CZ6508000000192000145399","debtorName":"Test Debtor","creditorIban":"DE89370400440532013000","creditorName":"Test Creditor","creditorBic":"COBADEFFXXX","amount":99.99,"currency":"EUR","endToEndId":"E2E-$key"}""",
                )
                .`when`().post("/api/v1/sepa-instant")
                .then().statusCode(201).body("status", equalTo("PROCESSING"))
                .extract().path<String>("paymentId"),
        )

        val pending = rowFor(paymentId)
        assertThat(pending.status).isEqualTo("PENDING")
        val sends = AtomicInteger()
        val publisher = mockk<OutboxEventPublisher>()
        coEvery { publisher.publish(any<OutboxEntry>()) } coAnswers {
            if (sends.incrementAndGet() == 1) error("simulated broker outage")
        }
        val dispatcher = SctInstOutboxDispatcher(repository, publisher, true, 250, mockk<DomainMetrics>(relaxed = true))

        tick(dispatcher)
        rowFor(paymentId).let { failed ->
            assertThat(failed.eventId).isEqualTo(pending.eventId)
            assertThat(failed.status).isEqualTo("FAILED")
            assertThat(failed.attempts).isEqualTo(1)
            assertThat(failed.sentAt).isNull()
        }
        assertThat(paymentStatus(paymentId)).isEqualTo("PROCESSING")
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE sct_inst_outbox SET next_attempt_at = now() - interval '1 second' WHERE event_id = ?",
            )
                .use { statement ->
                    statement.setObject(1, pending.eventId)
                    assertThat(statement.executeUpdate()).isEqualTo(1)
                }
        }

        tick(dispatcher)
        rowFor(paymentId).let { sent ->
            assertThat(sent.eventId).isEqualTo(pending.eventId)
            assertThat(sent.status).isEqualTo("SENT")
            assertThat(sent.attempts).isEqualTo(2)
            assertThat(sent.sentAt).isNotNull()
        }
        coVerify(exactly = 2) { publisher.publish(match { it.aggregateId == paymentId }) }
    }
}
