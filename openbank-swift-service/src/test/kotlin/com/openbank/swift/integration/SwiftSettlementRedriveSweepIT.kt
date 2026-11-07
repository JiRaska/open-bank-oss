// SPDX-License-Identifier: Apache-2.0

package com.openbank.swift.integration

import com.openbank.swift.application.port.out.SchemeGatewayPort
import com.openbank.swift.application.port.out.SchemeSubmissionOutcome
import com.openbank.swift.application.port.out.SettlementOutcome
import com.openbank.swift.application.port.out.SettlementPort
import com.openbank.swift.application.port.out.SettlementUnavailableException
import com.openbank.swift.domain.model.SwiftMessage
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * #13686: drives the REAL `SwiftSettlementRedriveScheduler` cron, not a direct call. A direct call supplies
 * the Vert.x context the scheduler itself must supply, so only this proves the sweep works as deployed.
 *
 * Two messages are accepted by the scheme and fail their only settlement call in transport. One is then
 * stopped for manual remediation with the runbook's stop statement. The sweep must book the other (proof
 * read back over JDBC) and must never call transaction-service for the stopped one. The profile's values are
 * literals: a QuarkusTestProfile loads in its own classloader.
 */
@QuarkusTest
@TestProfile(SwiftSettlementRedriveSweepIT.Profile::class)
@QuarkusTestResource(SwiftOperatorTransitionOutboxIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.swift.it.PostgresRedisTestResource::class)
@TestSecurity(user = "redrive-sweep-test", roles = ["ROLE_PAYMENTS"])
class SwiftSettlementRedriveSweepIT {
    class Profile : QuarkusTestProfile {
        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(AcceptedScheme::class.java, Ledger::class.java)
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.swift.scheme-submission.enabled" to "true",
            "openbank.outbox.dispatch-enabled" to "false",
            "openbank.swift.settlement-redrive.enabled" to "true",
            "openbank.swift.settlement-redrive.every" to "1s",
            "openbank.swift.settlement-redrive.delayed" to "1s",
            // Long enough to stop the second message before it is ever eligible.
            "openbank.swift.settlement-redrive.min-age" to "PT3S",
        )
    }

    @Alternative
    @ApplicationScoped
    class AcceptedScheme : SchemeGatewayPort {
        override suspend fun submit(message: SwiftMessage) = SchemeSubmissionOutcome(true, null, "<pacs.008/>")
    }

    /** The first call per message (the send) fails in transport; every later one is a re-drive and books. */
    @Alternative
    @ApplicationScoped
    class Ledger : SettlementPort {
        override suspend fun settle(message: SwiftMessage): SettlementOutcome {
            val n = calls.computeIfAbsent(message.id) { AtomicInteger() }.incrementAndGet()
            if (n == 1) throw SettlementUnavailableException("controlled transport failure")
            return SettlementOutcome(true, bookedId(message.id))
        }
    }

    @Inject
    lateinit var dataSource: DataSource

    @Test
    fun `the cron books a held message and never calls transaction-service for a stopped one`() {
        val booked = heldMessage()
        val stopped = heldMessage()
        // Runbook 0032 stop statement, before the message is eligible.
        assertThat(
            update(
                "UPDATE swift_messages SET booking_redrive_stopped_at = now(), " +
                    "booking_redrive_stop_reason = 'TICKET-1 approver=a executor=b' WHERE id = ? " +
                    "AND (booking_redrive_claimed_until IS NULL OR booking_redrive_claimed_until < now())",
                stopped,
            ),
        ).isEqualTo(1)

        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (count(PROVEN, booked) == 0 && System.nanoTime() < deadline) Thread.sleep(POLL_MS)

        assertThat(count(PROVEN, booked)).describedAs("held message booked by the cron").isEqualTo(1)
        assertThat(callsFor(booked)).isEqualTo(2)
        assertThat(
            count(
                "select count(*) from swift_outbox where aggregate_id = ? and payload::jsonb->>'status' = 'COMPLETED'",
                booked,
            ),
        ).isEqualTo(1)
        // The stopped message was eligible for several ticks after the booked one was proven: still no call.
        Thread.sleep(SETTLE_MS)
        assertThat(callsFor(stopped)).describedAs("re-drive calls for the stopped message").isEqualTo(1)
        assertThat(
            count(
                "select count(*) from swift_messages where id = ? and status = 'SENT' " +
                    "and booking_first_due_at is not null and booking_proven_at is null " +
                    "and booking_redrive_attempts = 0 and booking_redrive_claim is null",
                stopped,
            ),
        ).isEqualTo(1)
    }

    private fun callsFor(id: UUID): Int = calls[id]?.get() ?: 0

    private fun heldMessage(): UUID {
        val key = UUID.randomUUID()
        val body = """
            {"idempotencyKey":"$key","messageType":"MT103","senderBic":"OPBKCZPP",
            "receiverBic":"DEUTDEFF","transactionReference":"SW-${key.toString().take(10)}",
            "valueDate":"20261022","currency":"EUR","amountMinorUnits":125000,
            "orderingCustomerAccount":"DE89370400440532013000","orderingCustomerAccountId":"${UUID.randomUUID()}",
            "orderingCustomerName":"Alice","beneficiaryAccount":"GB33BUKB20201555555555",
            "beneficiaryName":"Bob","chargeCode":"SHA","priority":"NORMAL"}
        """.trimIndent()
        return UUID.fromString(
            given().contentType("application/json").body(body).post("/api/v1/swift")
                .then().statusCode(201).extract().path("id"),
        )
    }

    private fun update(sql: String, id: UUID): Int = dataSource.connection.use { c ->
        c.prepareStatement(sql).use { s ->
            s.setObject(1, id)
            s.executeUpdate()
        }
    }

    private fun count(sql: String, id: UUID): Int = dataSource.connection.use { c ->
        c.prepareStatement(sql).use { s ->
            s.setObject(1, id)
            s.executeQuery().use { r ->
                check(r.next())
                r.getInt(1)
            }
        }
    }

    companion object {
        private const val WAIT_SECONDS = 30L
        private const val POLL_MS = 200L
        private const val SETTLE_MS = 3_000L
        private const val PROVEN = "select count(*) from swift_messages where id = ? and status = 'COMPLETED' " +
            "and booking_proven_at is not null and booking_transaction_id is not null"

        val calls = ConcurrentHashMap<UUID, AtomicInteger>()

        fun bookedId(id: UUID): UUID = UUID.nameUUIDFromBytes("swift-settlement-$id".toByteArray())
    }
}
