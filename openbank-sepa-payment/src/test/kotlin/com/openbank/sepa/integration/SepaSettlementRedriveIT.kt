// SPDX-License-Identifier: Apache-2.0

package com.openbank.sepa.integration

import com.openbank.libs.domain.money.Money
import com.openbank.sepa.application.port.out.SepaBookingWrites
import com.openbank.sepa.application.port.out.SepaPaymentOutboxMessage
import com.openbank.sepa.application.port.out.SepaPaymentRepository
import com.openbank.sepa.application.port.out.SettlementOutcome
import com.openbank.sepa.application.port.out.SettlementPort
import com.openbank.sepa.application.workflow.SepaPaymentActivitiesImpl
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaPaymentType
import com.openbank.sepa.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.vertx.core.Vertx
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import kotlinx.coroutines.suspendCancellableCoroutine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * #13623: two concurrent re-drives of ONE accepted, still-unbooked PROCESSING payment, against a real
 * PostgreSQL. The settlement port is a stand-in that behaves like transaction-service under a replayed
 * key: both calls get the SAME transaction back. Both calls are held inside the port until both have
 * arrived, so both reach the locked completion write together. Exactly one proof and exactly one
 * COMPLETED status event may result.
 */
@QuarkusTest
@TestProfile(SepaSettlementRedriveIT.Profile::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
class SepaSettlementRedriveIT {

    class Profile : QuarkusTestProfile {
        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(ReplayingSettlement::class.java)
        override fun getConfigOverrides(): Map<String, String> = mapOf("quarkus.oidc-client.enabled" to "true")
    }

    /** Same answer for the same key, like transaction-service; counts calls and gates them together. */
    @Alternative
    @ApplicationScoped
    class ReplayingSettlement : SettlementPort {
        val calls = AtomicInteger()
        val acceptedAts = ConcurrentHashMap.newKeySet<Instant>()

        @Volatile
        var gate = CountDownLatch(0)

        override suspend fun settle(payment: SepaPayment, acceptedAt: Instant): SettlementOutcome {
            calls.incrementAndGet()
            acceptedAts.add(acceptedAt)
            gate.countDown()
            awaitGate()
            return SettlementOutcome(settled = true, transactionId = bookedId(payment.id))
        }

        /**
         * Waits for the other re-drive WITHOUT blocking: both activity calls may share one event loop, and the
         * completion write after this must resume on the same Vert.x context the activity runs on.
         */
        private suspend fun awaitGate() {
            val ctx = checkNotNull(Vertx.currentContext()) { "settlement must run on a Vert.x context" }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GATE_SECONDS)
            suspendCancellableCoroutine<Unit> { cont ->
                ctx.owner().setPeriodic(POLL_MS) { timer ->
                    val done = gate.count == 0L
                    if (done || System.nanoTime() > deadline) {
                        ctx.owner().cancelTimer(timer)
                        ctx.runOnContext {
                            if (done) {
                                cont.resume(
                                    Unit,
                                )
                            } else {
                                cont.resumeWithException(IllegalStateException("second re-drive never arrived"))
                            }
                        }
                    }
                }
            }
        }
    }

    @Inject
    lateinit var activities: SepaPaymentActivitiesImpl

    @Inject
    lateinit var settlement: ReplayingSettlement

    @Inject
    lateinit var dataSource: DataSource

    @Test
    fun `two concurrent re-drives of one held payment produce one proof and one COMPLETED event`() {
        val id = UUID.fromString(
            given().post("/test/settlement-redrive/accepted").then().statusCode(200).extract().asString(),
        )
        val dueAt = dueAt(id)
        assertThat(dueAt).isNotNull()
        settlement.calls.set(0)
        settlement.acceptedAts.clear()
        settlement.gate = CountDownLatch(2)

        val pool = Executors.newFixedThreadPool(2)
        val results = try {
            List(2) {
                pool.submit<SepaPaymentStatus> { activities.submitToScheme(id) }
            }.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        // Both re-drives really overlapped inside settlement, and both report success.
        assertThat(settlement.calls.get()).isEqualTo(2)
        assertThat(results).containsOnly(SepaPaymentStatus.COMPLETED)
        // Both re-drives asked for the booking against the durable due instant, never "now".
        assertThat(settlement.acceptedAts).containsExactly(dueAt)
        assertThat(
            count(
                "SELECT count(*) FROM sepa_payments WHERE payment_id = ? AND status = 'COMPLETED' " +
                    "AND booking_proven_at IS NOT NULL AND booking_transaction_id = '${bookedId(id)}'",
                id,
            ),
        ).isEqualTo(1)
        assertThat(
            count(
                "SELECT count(*) FROM sepa_payment_outbox WHERE aggregate_id = ? " +
                    "AND event_type = 'sepa.payment.status-changed' AND payload::jsonb->>'status' = 'COMPLETED'",
                id,
            ),
        )
            .isEqualTo(1)

        // A third, sequential re-drive after proof is a no-op: the clock is closed, nothing is called.
        settlement.gate = CountDownLatch(0)
        assertThat(activities.submitToScheme(id)).isEqualTo(SepaPaymentStatus.COMPLETED)
        assertThat(settlement.calls.get()).isEqualTo(2)
    }

    private fun dueAt(id: UUID): Instant? = dataSource.connection.use { c ->
        c.prepareStatement("SELECT booking_first_due_at FROM sepa_payments WHERE payment_id = ?").use { s ->
            s.setObject(1, id)
            s.executeQuery().use { r ->
                check(r.next())
                r.getTimestamp(1)?.toInstant()
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

    /** Test transport only. Builds an accepted PROCESSING payment with an open booking clock. */
    @ApplicationScoped
    @Path("/test/settlement-redrive")
    @RolesAllowed("ROLE_API")
    class Fixture(private val repository: SepaPaymentRepository, private val writes: SepaBookingWrites) {
        private fun event(next: SepaPayment) = SepaPaymentOutboxMessage(
            aggregateId = next.id,
            eventType = "sepa.payment.status-changed",
            payload = """{"status":"${next.status}","paymentId":"${next.id}"}""",
            createdAt = next.updatedAt,
        )

        @POST
        @Path("/accepted")
        suspend fun accepted(): String {
            val id = UUID.randomUUID()
            val now = Instant.now()
            val received = SepaPayment(
                id = id, idempotencyKey = "redrive-$id", type = SepaPaymentType.SCT,
                status = SepaPaymentStatus.VALIDATED, debtorAccountId = UUID.randomUUID(),
                debtorIban = "DE89370400440532013000", debtorName = "Synthetic payer",
                creditorIban = "FR1420041010050500013M02606", creditorName = "Synthetic payee",
                creditorBic = "BNPAFRPPXXX", amount = Money.of("42.50", "EUR"),
                remittanceInfo = null, endToEndId = "redrive-$id", rejectReason = null, rejectDetail = null,
                submittedAt = now, completedAt = null, createdAt = now, updatedAt = now,
            )
            repository.save(received, event(received))
            val validated = checkNotNull(repository.findById(id))
            val processing = validated.transitionTo(SepaPaymentStatus.PROCESSING, clock = Clock.systemUTC())
            writes.acceptForBooking(processing, event(processing))
            return id.toString()
        }
    }

    private companion object {
        fun bookedId(paymentId: UUID): UUID = UUID.nameUUIDFromBytes("sepa-settlement-$paymentId".toByteArray())
    }
}

private const val GATE_SECONDS = 20L
private const val POLL_MS = 10L
