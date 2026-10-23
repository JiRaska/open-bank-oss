// SPDX-License-Identifier: Apache-2.0

package com.openbank.swift.integration

import com.openbank.swift.application.port.out.SchemeGatewayPort
import com.openbank.swift.application.port.out.SchemeSubmissionOutcome
import com.openbank.swift.application.port.out.SettlementOutcome
import com.openbank.swift.application.port.out.SettlementPort
import com.openbank.swift.application.port.out.SettlementUnavailableException
import com.openbank.swift.application.port.out.SwiftBookingQuery
import com.openbank.swift.application.usecase.SwiftSettlementRedriveService
import com.openbank.swift.application.usecase.SwiftSettlementRedriveService.Result
import com.openbank.swift.domain.model.SwiftMessage
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.coroutines.uni
import io.vertx.core.Vertx
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.coroutines.resume

/**
 * #13686, real HTTP + PostgreSQL; only the scheme gateway and transaction-service are replaced.
 *
 * A message is accepted by the scheme, its only settlement call fails in transport, and it is held SENT
 * with an open booking clock. Two re-drives then race. The stand-in ledger answers like transaction-service
 * under a replayed key (the same transaction every time) and holds each call open until a second call
 * arrives or [HOLD_MS] passes, so if the claim let both re-drives through, both would be inside settlement
 * together. Exactly one call, one proof and one COMPLETED event may result.
 */
@QuarkusTest
@TestProfile(SwiftSettlementRedriveIT.Profile::class)
@QuarkusTestResource(SwiftOperatorTransitionOutboxIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.swift.it.PostgresRedisTestResource::class)
@TestSecurity(user = "redrive-test", roles = ["ROLE_PAYMENTS"])
class SwiftSettlementRedriveIT {
    class Profile : QuarkusTestProfile {
        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(AcceptedScheme::class.java, Ledger::class.java)
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.swift.scheme-submission.enabled" to "true",
            "openbank.outbox.dispatch-enabled" to "false",
            // The sweep stays off (%test); the test drives the re-drive itself, with no minimum age.
            "openbank.swift.settlement-redrive.min-age" to "PT0S",
            "openbank.swift.settlement-redrive.backoff-base" to "PT0.001S",
            "openbank.swift.settlement-redrive.backoff-max" to "PT0.001S",
            "openbank.swift.settlement-redrive.max-attempts" to "2",
        )
    }

    @Alternative
    @ApplicationScoped
    class AcceptedScheme : SchemeGatewayPort {
        override suspend fun submit(message: SwiftMessage): SchemeSubmissionOutcome {
            schemeCalls.incrementAndGet()
            return SchemeSubmissionOutcome(true, null, "<pacs.008/>")
        }
    }

    @Alternative
    @ApplicationScoped
    class Ledger : SettlementPort {
        override suspend fun settle(message: SwiftMessage): SettlementOutcome {
            valueDates.add(message.valueDate)
            return behavior(message)
        }
    }

    @Inject
    lateinit var redrive: SwiftSettlementRedriveService

    @Inject
    lateinit var query: SwiftBookingQuery

    @Inject
    lateinit var dataSource: DataSource

    private val ids = mutableListOf<UUID>()

    @BeforeEach
    fun reset() {
        behavior = { throw SettlementUnavailableException("controlled transport failure") }
        valueDates.clear()
        schemeCalls.set(0)
    }

    @AfterEach
    fun cleanup() {
        ids.forEach { id ->
            execute("delete from swift_outbox where aggregate_id = '$id'")
            execute("delete from swift_messages where id = '$id'")
        }
    }

    @Test
    fun `two concurrent re-drives of one held message make one call, one proof and one COMPLETED event`() {
        val id = heldMessage()
        assertThat(schemeCalls.get()).isEqualTo(1)
        val transactionId = UUID.nameUUIDFromBytes("swift-settlement-$id".toByteArray())
        val calls = AtomicInteger()
        val arrived = CountDownLatch(2)
        behavior = {
            calls.incrementAndGet()
            arrived.countDown()
            holdUntil(arrived)
            SettlementOutcome(true, transactionId)
        }
        valueDates.clear()

        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val results = try {
            List(2) {
                pool.submit<Result> {
                    start.await()
                    onContext { redrive.redrive(id) }
                }
            }.also { start.countDown() }.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertThat(calls.get()).describedAs("transaction-service calls").isEqualTo(1)
        assertThat(results).containsExactlyInAnyOrder(Result.SETTLED, Result.NOT_CLAIMED)
        // Never resubmitted to the scheme; the request carries the MT103's own 32A value date.
        assertThat(schemeCalls.get()).isEqualTo(1)
        assertThat(valueDates).containsExactly(VALUE_DATE_32A)
        assertThat(
            count(
                "select count(*) from swift_messages where id = ? and status = 'COMPLETED' " +
                    "and booking_proven_at is not null and booking_transaction_id = '$transactionId' " +
                    "and booking_redrive_claim is null",
                id,
            ),
        ).isEqualTo(1)
        assertThat(
            count(
                "select count(*) from swift_outbox where aggregate_id = ? and payload::jsonb->>'status' = 'COMPLETED'",
                id,
            ),
        ).isEqualTo(1)

        // The clock is closed: a later re-drive is not eligible and calls nothing.
        assertThat(onContext { redrive.redrive(id) }).isEqualTo(Result.NOT_CLAIMED)
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun `an unproven re-drive backs off, then exhausts with the booking clock still open`() {
        val id = heldMessage()
        valueDates.clear()

        assertThat(onContext { redrive.redrive(id) }).isEqualTo(Result.RESCHEDULED)
        assertThat(attempts(id)).isEqualTo(1)
        Thread.sleep(BACKOFF_SLEEP_MS)
        assertThat(onContext { redrive.redrive(id) }).isEqualTo(Result.EXHAUSTED)
        Thread.sleep(BACKOFF_SLEEP_MS)
        assertThat(onContext { redrive.redrive(id) }).isEqualTo(Result.NOT_CLAIMED)

        assertThat(valueDates).containsExactly(VALUE_DATE_32A, VALUE_DATE_32A)
        assertThat(schemeCalls.get()).isEqualTo(1)
        assertThat(
            count(
                "select count(*) from swift_messages where id = ? and status = 'SENT' " +
                    "and booking_redrive_attempts = 2 and booking_redrive_exhausted_at is not null " +
                    "and booking_first_due_at is not null and booking_proven_at is null",
                id,
            ),
        ).isEqualTo(1)
        // Exhausted is not resolved: the age gauge (and so SwiftBookingUnsettled) still sees it.
        assertThat(onContext { query.bookingAgeSnapshot() }.oldestDueAt).isNotNull()
    }

    /** POST the MT103; the scheme accepts and the only settlement call fails, so it is held SENT. */
    private fun heldMessage(): UUID {
        val key = UUID.randomUUID()
        val body = """
            {"idempotencyKey":"$key","messageType":"MT103","senderBic":"OPBKCZPP",
            "receiverBic":"DEUTDEFF","transactionReference":"RD-${key.toString().take(10)}",
            "valueDate":"$VALUE_DATE_32A","currency":"EUR","amountMinorUnits":125000,
            "orderingCustomerAccount":"DE89370400440532013000","orderingCustomerAccountId":"${UUID.randomUUID()}",
            "orderingCustomerName":"Alice","beneficiaryAccount":"GB33BUKB20201555555555",
            "beneficiaryName":"Bob","chargeCode":"SHA","priority":"NORMAL"}
        """.trimIndent()
        val id = UUID.fromString(
            given().contentType("application/json").body(body).post("/api/v1/swift")
                .then().statusCode(201).extract().path("id"),
        )
        synchronized(ids) { ids.add(id) }
        assertThat(
            count(
                "select count(*) from swift_messages where id = ? and status = 'SENT' " +
                    "and booking_first_due_at is not null and booking_proven_at is null",
                id,
            ),
        ).isEqualTo(1)
        return id
    }

    private fun <T> onContext(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun attempts(id: UUID): Int = dataSource.connection.use { c ->
        c.prepareStatement("select booking_redrive_attempts from swift_messages where id = ?").use { s ->
            s.setObject(1, id)
            s.executeQuery().use { r ->
                check(r.next())
                r.getInt(1)
            }
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

    private fun execute(sql: String) {
        dataSource.connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
    }

    companion object {
        private const val VALUE_DATE_32A = "20261022"
        private const val HOLD_MS = 3_000L
        private const val POLL_MS = 10L
        private const val BACKOFF_SLEEP_MS = 50L

        @Volatile
        var behavior: suspend (SwiftMessage) -> SettlementOutcome = { SettlementOutcome(false, null) }

        val valueDates: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val schemeCalls = AtomicInteger()

        /**
         * Waits for a second settlement call (or [HOLD_MS]) WITHOUT blocking the event loop, and resumes on the
         * caller's Vert.x context so the completion write that follows still has its session.
         */
        suspend fun holdUntil(arrived: CountDownLatch) {
            val ctx = checkNotNull(Vertx.currentContext()) { "settlement must run on a Vert.x context" }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HOLD_MS)
            suspendCancellableCoroutine { cont ->
                ctx.owner().setPeriodic(POLL_MS) { timer ->
                    if (arrived.count == 0L || System.nanoTime() > deadline) {
                        ctx.owner().cancelTimer(timer)
                        ctx.runOnContext { cont.resume(Unit) }
                    }
                }
            }
        }
    }
}
