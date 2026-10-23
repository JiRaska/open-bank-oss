// SPDX-License-Identifier: Apache-2.0

package com.openbank.sepa.integration

import com.openbank.sepa.application.port.out.SepaBookingQuery
import com.openbank.sepa.application.port.out.SepaBookingWrites
import com.openbank.sepa.application.port.out.SepaPaymentOutboxMessage
import com.openbank.sepa.application.port.out.SepaPaymentRepository
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Real HTTP/real PostgreSQL proof of local metadata and outbox atomicity, not a live ledger test.
 * The test-only endpoint supplies an explicitly known transaction ID to the internal completion port;
 * strict provider response proof remains covered by SettlementAdapterTest and the production caller.
 */
@QuarkusTest
@TestProfile(SepaPaymentReturnAuditEvidenceIT.Profile::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
class SepaBookingClockIT {
    @Inject
    lateinit var dataSource: DataSource

    @Test
    fun `due receipt precedes completion and both metadata writes roll back with their outbox`() {
        val id = create()
        failOutbox(id, "PROCESSING") {
            given().post("/test/booking-clock/$id/accept").then().statusCode(409)
            assertThat(row(id)["status"]).isEqualTo("VALIDATED")
            assertThat(row(id)["booking_first_due_at"]).isNull()
        }
        given().post("/test/booking-clock/$id/accept").then().statusCode(200)
        val pending = row(id)
        assertThat(pending["booking_first_due_at"]).isNotNull()
        assertThat(pending["booking_proven_at"]).isNull()
        assertThat(pending["status"]).isEqualTo("PROCESSING")
        assertThat(pending["payment_xmin"]).isEqualTo(statusOutboxXmin(id, "PROCESSING"))
        val tx = UUID.randomUUID()
        failOutbox(id, "COMPLETED") {
            given().queryParam("tx", tx.toString()).post("/test/booking-clock/$id/complete").then().statusCode(409)
            assertThat(row(id)).isEqualTo(pending)
        }
        given().queryParam("tx", tx.toString()).post("/test/booking-clock/$id/complete").then().statusCode(200)
        val proven = row(id)
        assertThat(proven["booking_first_due_at"]).isEqualTo(pending["booking_first_due_at"])
        assertThat(proven["booking_proven_at"]).isNotNull()
        assertThat(proven["booking_transaction_id"]).isEqualTo(tx.toString())
        assertThat(proven["payment_xmin"]).isEqualTo(statusOutboxXmin(id, "COMPLETED"))
    }

    @Test
    fun `general status writes cannot clear pending age and legacy rows remain explicitly unknown`() {
        val id = create()
        given().post("/test/booking-clock/$id/accept").then().statusCode(200)
        val due = row(id)["booking_first_due_at"]
        given().post("/test/booking-clock/$id/generic-settled").then().statusCode(200)
        assertThat(row(id)["booking_first_due_at"]).isEqualTo(due)
        assertThat(row(id)["booking_proven_at"]).isNull()
        // Query must not hide a pending marker simply because another writer changed business status.
        sql { connection ->
            connection.prepareStatement("UPDATE sepa_payments SET booking_first_due_at = ? WHERE payment_id = ?")
                .use { statement ->
                    statement.setObject(1, java.time.OffsetDateTime.parse("2020-01-01T00:00:00Z"))
                    statement.setObject(2, id)
                    statement.executeUpdate()
                }
        }
        val snapshot = given().get("/test/booking-clock/snapshot").then().statusCode(200).extract().jsonPath()
        assertThat(snapshot.getString("oldestDueAt")).isEqualTo("2020-01-01T00:00:00Z")
        val before = snapshot.getLong("unknownLegacyCount")
        val legacy = create()
        given().post("/test/booking-clock/$legacy/legacy-sent").then().statusCode(200)
        assertThat(row(legacy)["booking_first_due_at"]).isNull()
        val after = given().get("/test/booking-clock/snapshot").then().statusCode(200).extract().jsonPath()
        assertThat(after.getLong("unknownLegacyCount")).isEqualTo(before + 1)
        given().post("/test/booking-clock/$legacy/generic-settled").then().statusCode(200)
        val terminal = given().get("/test/booking-clock/snapshot").then().statusCode(200).extract().jsonPath()
        assertThat(terminal.getLong("unknownLegacyCount")).isEqualTo(before + 1)
        // Simulate pre-migration rows, including terminal business states: no booking proof is inferred.
        listOf("COMPLETED", "REJECTED", "CANCELLED").forEach { status ->
            val historical = create()
            sql { connection ->
                connection.prepareStatement(
                    "UPDATE sepa_payments SET status = ?, booking_clock_covered = NULL WHERE payment_id = ?",
                ).use { statement ->
                    statement.setString(1, status)
                    statement.setObject(2, historical)
                    statement.executeUpdate()
                }
            }
        }
        val history = given().get("/test/booking-clock/snapshot").then().statusCode(200).extract().jsonPath()
        assertThat(history.getLong("unknownLegacyCount")).isEqualTo(before + 4)
    }

    @Test
    fun `committed due marker is visible while a modeled provider is outstanding`() {
        val id = create()
        given().post("/test/booking-clock/$id/accept").then().statusCode(200)
        val pending = row(id)
        Latch.paymentId = id
        Latch.entered = java.util.concurrent.CountDownLatch(1)
        Latch.release = kotlinx.coroutines.CompletableDeferred()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val response = executor.submit<Int> {
                given().queryParam("tx", UUID.randomUUID().toString())
                    .post("/test/booking-clock/$id/complete").statusCode()
            }
            assertThat(Latch.entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
            assertThat(row(id)).isEqualTo(pending)
            given().post("/test/booking-clock/$id/pending-return").then().statusCode(200)
            val refund = row(id)
            assertThat(refund["return_first_received_at"]).isNotNull()
            assertThat(refund["return_resolved_at"]).isNull()
            Latch.release.complete(Unit)
            assertThat(response.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200)
            val completed = row(id)
            assertThat(completed["booking_first_due_at"]).isEqualTo(pending["booking_first_due_at"])
            assertThat(completed["return_first_received_at"]).isEqualTo(refund["return_first_received_at"])
            assertThat(completed["return_resolved_at"]).isNull()
            assertThat(completed["return_original_transaction_id"]).isEqualTo(completed["booking_transaction_id"])
        } finally {
            Latch.release.complete(Unit)
            Latch.paymentId = null
            executor.shutdownNow()
        }
    }

    object Latch {
        @Volatile var paymentId: UUID? = null
        var entered = java.util.concurrent.CountDownLatch(1)
        var release = kotlinx.coroutines.CompletableDeferred<Unit>()
    }

    @Test
    fun `proven booking is excluded from pending oldest query`() {
        val id = create()
        given().post("/test/booking-clock/$id/accept").then().statusCode(200)
        sql { connection ->
            connection.prepareStatement("UPDATE sepa_payments SET booking_first_due_at = ? WHERE payment_id = ?")
                .use { statement ->
                    statement.setObject(1, java.time.OffsetDateTime.parse("1990-01-01T00:00:00Z"))
                    statement.setObject(2, id)
                    statement.executeUpdate()
                }
        }
        assertThat(given().get("/test/booking-clock/snapshot").jsonPath().getString("oldestDueAt"))
            .isEqualTo("1990-01-01T00:00:00Z")
        given().queryParam("tx", UUID.randomUUID().toString())
            .post("/test/booking-clock/$id/complete").then().statusCode(200)
        assertThat(given().get("/test/booking-clock/snapshot").jsonPath().getString("oldestDueAt"))
            .isNotEqualTo("1990-01-01T00:00:00Z")
    }

    private fun create(): UUID = UUID.fromString(
        given().post("/test/booking-clock/create").then().statusCode(200).extract().asString(),
    )

    private fun <T> sql(block: (Connection) -> T): T = dataSource.connection.use(block)

    private fun row(id: UUID): Map<String, String?> = sql { connection ->
        connection.prepareStatement(
            "SELECT status, booking_first_due_at, booking_proven_at, booking_transaction_id, " +
                "return_first_received_at, return_resolved_at, return_original_transaction_id, " +
                "xmin::text AS payment_xmin FROM sepa_payments WHERE payment_id = ?",
        ).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { result ->
                check(result.next())
                listOf(
                    "status",
                    "booking_first_due_at",
                    "booking_proven_at",
                    "booking_transaction_id",
                    "payment_xmin",
                    "return_first_received_at",
                    "return_resolved_at",
                    "return_original_transaction_id",
                )
                    .associateWith { result.getString(it) }
            }
        }
    }

    private fun statusOutboxXmin(id: UUID, status: String): String = sql { connection ->
        connection.prepareStatement(
            "SELECT xmin::text FROM sepa_payment_outbox WHERE aggregate_id = ? " +
                "AND event_type = 'sepa.payment.status-changed' AND payload::jsonb->>'status' = ?",
        ).use { statement ->
            statement.setObject(1, id)
            statement.setString(2, status)
            statement.executeQuery().use { result ->
                check(result.next())
                result.getString(1)
            }
        }
    }

    private fun failOutbox(id: UUID, status: String, body: () -> Unit) {
        val constraint = "clock_${id.toString().replace("-", "")}_$status".lowercase()
        fun execute(query: String) = sql { it.createStatement().use { statement -> statement.execute(query) } }
        execute(
            "ALTER TABLE sepa_payment_outbox ADD CONSTRAINT $constraint CHECK " +
                "(aggregate_id <> '$id' OR payload::jsonb->>'status' <> '$status')",
        )
        try {
            body()
        } finally {
            execute("ALTER TABLE sepa_payment_outbox DROP CONSTRAINT $constraint")
        }
    }

    /** Test transport only, never packaged in production. Real repository ports run on the HTTP Vert.x context. */
    @ApplicationScoped
    @Path("/test/booking-clock")
    @RolesAllowed("ROLE_API")
    class FixtureEndpoint(
        private val repository: SepaPaymentRepository,
        private val writes: SepaBookingWrites,
        private val query: SepaBookingQuery,
    ) {
        private fun event(before: SepaPayment, next: SepaPayment) = SepaPaymentOutboxMessage(
            aggregateId = next.id,
            eventType = "sepa.payment.status-changed",
            payload = """{"status":"${next.status}","paymentId":"${before.id}"}""",
            createdAt = next.updatedAt,
        )

        @POST
        @Path("/create")
        suspend fun create(): String {
            val id = UUID.randomUUID()
            val now = Instant.now()
            val payment = SepaPayment(
                id = id, idempotencyKey = "clock-$id", type = com.openbank.sepa.domain.model.SepaPaymentType.SCT,
                status = SepaPaymentStatus.VALIDATED, debtorAccountId = UUID.randomUUID(),
                debtorIban = "DE89370400440532013000", debtorName = "Synthetic payer",
                creditorIban = "FR1420041010050500013M02606", creditorName = "Synthetic payee",
                creditorBic = "BNPAFRPPXXX", amount = com.openbank.libs.domain.money.Money.of("42.50", "EUR"),
                remittanceInfo = null, endToEndId = "clock-$id", rejectReason = null, rejectDetail = null,
                submittedAt = now, completedAt = null, createdAt = now, updatedAt = now,
            )
            repository.save(payment, event(payment, payment))
            return id.toString()
        }

        private suspend fun validated(id: UUID): SepaPayment {
            val before = checkNotNull(repository.findById(id))
            if (before.status == SepaPaymentStatus.VALIDATED) return before
            val next = before.transitionTo(SepaPaymentStatus.VALIDATED, clock = Clock.systemUTC())
            return repository.update(next, event(before, next))
        }

        @POST
        @Path("/{id}/accept")
        suspend fun accept(@PathParam("id") id: UUID): String {
            val before = validated(id)
            val next = before.transitionTo(SepaPaymentStatus.PROCESSING, clock = Clock.systemUTC())
            writes.acceptForBooking(next, event(before, next))
            return "accepted"
        }

        @POST
        @Path("/{id}/pending-return")
        suspend fun pendingReturn(@PathParam("id") id: UUID): String {
            repository.beginReturn(
                id,
                com.openbank.sepa.domain.model.SepaReturnIntent(
                    originalTransactionId = null,
                    firstReceivedAt = Instant.now(),
                    reasonCode = "AM09",
                    actorId = "synthetic-booking-clock",
                    actorType = "SYSTEM",
                    correlationId = id.toString(),
                ),
            )
            return "pending"
        }

        @POST
        @Path("/{id}/complete")
        suspend fun complete(@PathParam("id") id: UUID, @QueryParam("tx") tx: UUID?): String {
            val before = checkNotNull(repository.findById(id))
            if (Latch.paymentId == id) {
                Latch.entered.countDown()
                Latch.release.await()
            }
            repository.completeSettlement(before, requireNotNull(tx), Instant.now()) { next -> event(before, next) }
            return "completed"
        }

        @POST
        @Path("/{id}/generic-settled")
        suspend fun genericSettled(@PathParam("id") id: UUID): String {
            val before = checkNotNull(repository.findById(id))
            val next = before.transitionTo(SepaPaymentStatus.COMPLETED, clock = Clock.systemUTC())
            repository.update(next, event(before, next))
            return "generic"
        }

        @POST
        @Path("/{id}/legacy-sent")
        suspend fun legacy(@PathParam("id") id: UUID): String {
            val before = validated(id)
            val next = before.transitionTo(SepaPaymentStatus.PROCESSING, clock = Clock.systemUTC())
            repository.update(next, event(before, next))
            return "legacy"
        }

        @GET
        @Path("/snapshot")
        suspend fun snapshot(): Map<String, Any?> {
            val snapshot = query.bookingAgeSnapshot()
            return mapOf(
                "oldestDueAt" to snapshot.oldestDueAt?.toString(),
                "unknownLegacyCount" to snapshot.unknownLegacyCount,
            )
        }
    }
}
