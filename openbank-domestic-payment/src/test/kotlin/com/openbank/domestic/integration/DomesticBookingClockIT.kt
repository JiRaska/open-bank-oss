// SPDX-License-Identifier: Apache-2.0

package com.openbank.domestic.integration

import com.openbank.domestic.application.port.out.DomesticBookingQuery
import com.openbank.domestic.application.port.out.DomesticBookingWrites
import com.openbank.domestic.application.port.out.DomesticPaymentEventPublisher
import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
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
import java.util.UUID
import javax.sql.DataSource

/** Real HTTP/real PostgreSQL proof of local metadata and outbox atomicity, not a live ledger test.
 * The test-only endpoint supplies an explicitly known transaction ID to the internal completion port;
 * strict provider response proof remains covered by SettlementAdapterTest and the production caller.
 */
@QuarkusTest
@QuarkusTestResource(DomesticPaymentOutboxAtomicityIT.NoDispatchInMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_domestic_payment_it")],
)
@TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_PAYMENTS"])
@OidcSecurity(
    claims = [
        Claim(key = "azp", value = "openbank-admin-ui"),
        Claim(key = "sub", value = "00000000-0000-0000-0000-000000000099"),
        Claim(key = "preferred_username", value = "00000000-0000-0000-0000-000000000099"),
    ],
)
class DomesticBookingClockIT {
    @Inject
    lateinit var dataSource: DataSource

    @Test
    fun `due receipt precedes completion and both metadata writes roll back with their outbox`() {
        val id = create()
        failOutbox(id, "SENT_TO_CLEARING") {
            given().post("/test/booking-clock/$id/accept").then().statusCode(409)
            assertThat(row(id)["status"]).isEqualTo("VALIDATED")
            assertThat(row(id)["booking_first_due_at"]).isNull()
        }
        given().post("/test/booking-clock/$id/accept").then().statusCode(200)
        val pending = row(id)
        assertThat(pending["booking_first_due_at"]).isNotNull()
        assertThat(pending["booking_proven_at"]).isNull()
        assertThat(pending["status"]).isEqualTo("SENT_TO_CLEARING")
        assertThat(pending["payment_xmin"]).isEqualTo(statusOutboxXmin(id, "SENT_TO_CLEARING"))
        val tx = UUID.randomUUID()
        failOutbox(id, "SETTLED") {
            given().queryParam("tx", tx.toString()).post("/test/booking-clock/$id/complete").then().statusCode(409)
            assertThat(row(id)).isEqualTo(pending)
        }
        given().queryParam("tx", tx.toString()).post("/test/booking-clock/$id/complete").then().statusCode(200)
        val proven = row(id)
        assertThat(proven["booking_first_due_at"]).isEqualTo(pending["booking_first_due_at"])
        assertThat(proven["booking_proven_at"]).isNotNull()
        assertThat(proven["booking_transaction_id"]).isEqualTo(tx.toString())
        assertThat(proven["payment_xmin"]).isEqualTo(statusOutboxXmin(id, "SETTLED"))
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
            connection.prepareStatement("UPDATE domestic_payments SET booking_first_due_at = ? WHERE payment_id = ?")
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
        listOf("SETTLED", "REJECTED", "CANCELLED").forEach { status ->
            val historical = create()
            sql { connection ->
                connection.prepareStatement(
                    "UPDATE domestic_payments SET status = ?, booking_clock_covered = NULL WHERE payment_id = ?",
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

    private fun create(): UUID {
        val response = given().contentType("application/json").header("Idempotency-Key", "clock-${UUID.randomUUID()}")
            .body(
                """{"debtorAccountId":"${UUID.randomUUID()}","debtorAccountNumber":"1234567890",
                "debtorBankCode":"0800","debtorName":"Synthetic payer","creditorAccountNumber":"9876543210",
                "creditorBankCode":"0100","creditorName":"Synthetic payee","amount":1234.56,"currency":"CZK",
                "priority":"STANDARD","endToEndId":"CLOCK-IT"}""",
            ).post("/api/v1/domestic-payments")
        response.then().statusCode(201)
        return UUID.fromString(response.jsonPath().getString("id"))
    }

    private fun <T> sql(block: (Connection) -> T): T = dataSource.connection.use(block)

    private fun row(id: UUID): Map<String, String?> = sql { connection ->
        connection.prepareStatement(
            "SELECT status, booking_first_due_at, booking_proven_at, booking_transaction_id, " +
                "xmin::text AS payment_xmin FROM domestic_payments WHERE payment_id = ?",
        ).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { result ->
                check(result.next())
                listOf("status", "booking_first_due_at", "booking_proven_at", "booking_transaction_id", "payment_xmin")
                    .associateWith { result.getString(it) }
            }
        }
    }

    private fun statusOutboxXmin(id: UUID, status: String): String = sql { connection ->
        connection.prepareStatement(
            "SELECT xmin::text FROM domestic_payment_outbox WHERE aggregate_id = ? " +
                "AND event_type = 'domestic.payment.status-changed' AND payload::jsonb->>'newStatus' = ?",
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
            "ALTER TABLE domestic_payment_outbox ADD CONSTRAINT $constraint CHECK " +
                "(aggregate_id <> '$id' OR payload::jsonb->>'newStatus' <> '$status')",
        )
        try {
            body()
        } finally {
            execute("ALTER TABLE domestic_payment_outbox DROP CONSTRAINT $constraint")
        }
    }

    /** Test transport only, never packaged in production. Real repository ports run on the HTTP Vert.x context. */
    @ApplicationScoped
    @Path("/test/booking-clock")
    @RolesAllowed("ROLE_PAYMENTS")
    class FixtureEndpoint(
        private val repository: DomesticPaymentRepository,
        private val writes: DomesticBookingWrites,
        private val query: DomesticBookingQuery,
        private val events: DomesticPaymentEventPublisher,
    ) {
        private fun event(before: DomesticPayment, next: DomesticPayment) = OutboxMessage(
            aggregateId = next.id,
            eventType = "domestic.payment.status-changed",
            payload = events.statusChangedPayload(before, next),
        )

        private suspend fun validated(id: UUID): DomesticPayment {
            val before = checkNotNull(repository.findById(id))
            if (before.status == DomesticPaymentStatus.VALIDATED) return before
            val next = before.transitionTo(DomesticPaymentStatus.VALIDATED, clock = Clock.systemUTC())
            return repository.update(next, event(before, next))
        }

        @POST
        @Path("/{id}/accept")
        suspend fun accept(@PathParam("id") id: UUID): String {
            val before = validated(id)
            val next = before.transitionTo(DomesticPaymentStatus.SENT_TO_CLEARING, clock = Clock.systemUTC())
            writes.acceptForBooking(next, event(before, next))
            return "accepted"
        }

        @POST
        @Path("/{id}/complete")
        suspend fun complete(@PathParam("id") id: UUID, @QueryParam("tx") tx: UUID?): String {
            val before = checkNotNull(repository.findById(id))
            val next = before.transitionTo(DomesticPaymentStatus.SETTLED, clock = Clock.systemUTC())
            writes.completeBooking(next, requireNotNull(tx), event(before, next))
            return "completed"
        }

        @POST
        @Path("/{id}/generic-settled")
        suspend fun genericSettled(@PathParam("id") id: UUID): String {
            val before = checkNotNull(repository.findById(id))
            val next = before.transitionTo(DomesticPaymentStatus.SETTLED, clock = Clock.systemUTC())
            repository.update(next, event(before, next))
            return "generic"
        }

        @POST
        @Path("/{id}/legacy-sent")
        suspend fun legacy(@PathParam("id") id: UUID): String {
            val before = validated(id)
            val next = before.transitionTo(DomesticPaymentStatus.SENT_TO_CLEARING, clock = Clock.systemUTC())
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
