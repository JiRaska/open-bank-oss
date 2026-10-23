// SPDX-License-Identifier: Apache-2.0

package com.openbank.swift.integration

import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.swift.application.port.out.SchemeGatewayPort
import com.openbank.swift.application.port.out.SchemeSubmissionOutcome
import com.openbank.swift.application.port.out.SettlementOutcome
import com.openbank.swift.application.port.out.SettlementPort
import com.openbank.swift.application.port.out.SettlementUnavailableException
import com.openbank.swift.application.port.out.SwiftBookingQuery
import com.openbank.swift.application.port.out.SwiftRepository
import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.domain.model.SwiftMessageType
import com.openbank.swift.domain.model.SwiftStatus
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.coroutines.uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/** Real HTTP + PostgreSQL; only the external scheme and ledger ports are replaced. */
@QuarkusTest
@TestProfile(SwiftBookingClockIT.Profile::class)
@QuarkusTestResource(SwiftOperatorTransitionOutboxIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.swift.it.PostgresRedisTestResource::class)
@TestSecurity(user = "booking-test", roles = ["ROLE_PAYMENTS"])
class SwiftBookingClockIT {
    class Profile : QuarkusTestProfile {
        override fun getEnabledAlternatives(): Set<Class<*>> =
            setOf(AcceptedScheme::class.java, Ledger::class.java, NanoClock::class.java)
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.swift.scheme-submission.enabled" to "true",
            "openbank.outbox.dispatch-enabled" to "false",
        )
    }

    @Alternative
    @ApplicationScoped
    class NanoClock : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = NANO_NOW
    }

    @Alternative
    @ApplicationScoped
    class AcceptedScheme : SchemeGatewayPort {
        override suspend fun submit(message: SwiftMessage): SchemeSubmissionOutcome {
            schemeObservation(message)
            return SchemeSubmissionOutcome(true, null, "<pacs.008/>")
        }
    }

    @Alternative
    @ApplicationScoped
    class Ledger : SettlementPort {
        override suspend fun settle(message: SwiftMessage): SettlementOutcome = behavior(message)
    }

    @Inject
    lateinit var repository: SwiftRepository

    @Inject
    lateinit var query: SwiftBookingQuery

    @Inject
    lateinit var dataSource: DataSource

    private val ids = mutableListOf<UUID>()

    @BeforeEach
    fun resetProvider() {
        schemeObservation = {}
        behavior = { SettlementOutcome(false, null) }
    }

    @AfterEach
    fun cleanup() {
        ids.forEach { id ->
            execute("delete from swift_outbox where aggregate_id = '$id'")
            execute("delete from swift_messages where id = '$id'")
        }
        behavior = { SettlementOutcome(false, null) }
    }

    private fun <T> onContext(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    @Test
    fun `nanosecond clock send uses canonical persisted creation time before accepted booking`() {
        var providerCalled = false
        val submitted = AtomicReference<SwiftMessage>()
        schemeObservation = { submitted.set(it) }
        val transactionId = UUID.randomUUID()
        behavior = { message ->
            providerCalled = true
            val stored = storedInstant(message.id, "created_at")
            assertThat(message.createdAt).isEqualTo(stored)
            assertThat(stored).isNotEqualTo(NANO_NOW)
            assertThat(storedInstant(message.id, "booking_first_due_at")).isNotNull()
            SettlementOutcome(true, transactionId)
        }
        val id = send()
        assertThat(submitted.get().createdAt)
            .describedAs("generic save must return the stored creation timestamp before scheme acceptance")
            .isEqualTo(storedInstant(id, "created_at"))
        assertThat(providerCalled).isTrue()
        assertThat(row(id)["status"]).isEqualTo("COMPLETED")
        assertThat(row(id)["booking_transaction_id"]).isEqualTo(transactionId.toString())
    }

    @Test
    fun `due is committed before provider and proof status and outbox commit atomically`() {
        val transactionId = UUID.randomUUID()
        behavior = { message ->
            val pending = row(message.id)
            assertThat(pending["status"]).isEqualTo("SENT")
            assertThat(pending["booking_first_due_at"]).isNotNull()
            assertThat(pending["booking_proven_at"]).isNull()
            SettlementOutcome(true, transactionId)
        }
        val id = send()
        val completed = row(id)
        assertThat(completed["status"]).isEqualTo("COMPLETED")
        assertThat(completed["booking_transaction_id"]).isEqualTo(transactionId.toString())
        assertThat(completed["booking_proven_at"]).isNotNull()
        assertThat(completed["xmin"]).isEqualTo(completedOutboxXmin(id))
        val current = onContext { checkNotNull(repository.findById(id)) }
        onContext { repository.completeBooking(current, transactionId, completedEvent(current)) }
        assertThat(row(id)).isEqualTo(completed)
        assertThat(completedEvents(id)).isEqualTo(1)
        assertThatThrownBy {
            onContext { repository.completeBooking(current, UUID.randomUUID(), completedEvent(current)) }
        }.satisfies(
            java.util.function.Consumer<Throwable> { failure ->
                assertThat(generateSequence(failure) { it.cause }.any { it is ResourceConflictException }).isTrue()
            },
        )
    }

    @Test
    fun `missing account no-op and unavailable provider retain pending clock across ACK and reject`() {
        val missing = send(account = null)
        assertThat(row(missing)["booking_first_due_at"]).isNotNull()
        assertThat(row(missing)["booking_proven_at"]).isNull()
        behavior = { throw SettlementUnavailableException("controlled provider refusal") }
        val id = send()
        val before = row(id)["booking_first_due_at"]
        operator(id, "ack", "ackRef")
        operator(id, "reject", "reason")
        val after = row(id)
        assertThat(after["status"]).isEqualTo("REJECTED")
        assertThat(after["booking_first_due_at"]).isEqualTo(before)
        assertThat(after["booking_proven_at"]).isNull()
        assertThat(onContext { query.bookingAgeSnapshot() }.oldestDueAt).isNotNull()
        assertThat(completedEvents(id)).isZero()
    }

    @Test
    fun `operator callback while provider books preserves callback state and unresolved local proof`() {
        val started = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val inFlight = AtomicReference<SwiftMessage>()
        behavior = { message ->
            inFlight.set(message)
            started.countDown()
            withTimeout(30_000) { release.await() }
            SettlementOutcome(true, UUID.randomUUID())
        }
        val result = CompletableFuture.supplyAsync { send() }
        try {
            check(started.await(30, TimeUnit.SECONDS))
            val id = inFlight.get().id
            operator(id, "ack", "ackRef")
            release.complete(Unit)
            assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(id)
            val current = row(id)
            assertThat(current["status"]).isEqualTo("ACKNOWLEDGED")
            assertThat(current["booking_first_due_at"]).isNotNull()
            assertThat(current["booking_proven_at"]).isNull()
            assertThat(current["booking_transaction_id"]).isNull()
            assertThat(completedEvents(id)).isZero()
            assertThat(onContext { query.bookingAgeSnapshot() }.oldestDueAt).isNotNull()
        } finally {
            release.complete(Unit)
            result.get(30, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `outbox failure rolls local proof and completed state back together`() {
        val constraint = "booking_" + UUID.randomUUID().toString().replace("-", "")
        val transactionId = UUID.randomUUID()
        behavior = { message ->
            execute(
                "alter table swift_outbox add constraint $constraint check " +
                    "(aggregate_id <> '${message.id}' or payload not like '%COMPLETED%')",
            )
            SettlementOutcome(true, transactionId)
        }
        val id = try {
            send()
        } finally {
            execute("alter table swift_outbox drop constraint if exists $constraint")
        }
        val pending = row(id)
        assertThat(pending["status"]).isEqualTo("SENT")
        assertThat(pending["booking_first_due_at"]).isNotNull()
        assertThat(pending["booking_proven_at"]).isNull()
        assertThat(pending["booking_transaction_id"]).isNull()
        assertThat(completedEvents(id)).isZero()
        val current = onContext { checkNotNull(repository.findById(id)) }
        val completed = current.copy(status = SwiftStatus.COMPLETED, updatedAt = Instant.now())
        onContext { repository.completeBooking(completed, transactionId, completedEvent(completed)) }
        assertThat(row(id)["booking_transaction_id"]).isEqualTo(transactionId.toString())
        assertThat(row(id)["xmin"]).isEqualTo(completedOutboxXmin(id))
    }

    @Test
    fun `booking port refuses unrelated aggregate event and payload identities without changing obligation`() {
        val id = send()
        val before = row(id)
        val current = onContext { checkNotNull(repository.findById(id)) }
        val completed = current.copy(status = SwiftStatus.COMPLETED, updatedAt = NANO_NOW)
        val event = completedEvent(completed)
        val invalid = listOf(
            event.copy(aggregateId = UUID.randomUUID()),
            event.copy(eventType = "unrelated.event"),
            event.copy(payload = event.payload.replace(id.toString(), UUID.randomUUID().toString())),
            event.copy(payload = event.payload.replace("COMPLETED", "SENT")),
        )
        invalid.forEach { outbox ->
            assertThatThrownBy {
                onContext { repository.completeBooking(completed, UUID.randomUUID(), outbox) }
            }.satisfies(
                java.util.function.Consumer<Throwable> { failure ->
                    assertThat(generateSequence(failure) { it.cause }.any { it is ResourceConflictException }).isTrue()
                },
            )
            assertThat(row(id)).isEqualTo(before)
            assertThat(completedEvents(id)).isZero()
        }
    }

    @Test
    fun `unknown legacy coverage is separate and callback status retains tracked minimum`() {
        val baseline = onContext { query.bookingAgeSnapshot() }.unknownLegacyCount
        val known = send()
        val legacy = send()
        execute("update swift_messages set booking_first_due_at = '2000-01-01T00:00:00Z' where id = '$known'")
        execute(
            "update swift_messages set booking_first_due_at = null, booking_clock_covered = null where id = '$legacy'",
        )
        operator(known, "ack", "ackRef")
        val snapshot = onContext { query.bookingAgeSnapshot() }
        assertThat(snapshot.oldestDueAt).isEqualTo(Instant.parse("2000-01-01T00:00:00Z"))
        assertThat(snapshot.unknownLegacyCount).isEqualTo(baseline + 1)
    }

    @Test
    fun `booking query excludes non MT103 legacy rows while MT103 every status remains unknown`() {
        val baseline = onContext { query.bookingAgeSnapshot() }
        SwiftMessageType.entries.filter { it != SwiftMessageType.MT103 }.forEach { type ->
            val id = send(messageType = type)
            execute("update swift_messages set booking_clock_covered = null where id = '$id'")
        }
        val nonBooking = onContext { query.bookingAgeSnapshot() }
        assertThat(nonBooking.unknownLegacyCount).isEqualTo(baseline.unknownLegacyCount)
        assertThat(nonBooking.oldestDueAt).isEqualTo(baseline.oldestDueAt)
        val unrelated = send(messageType = SwiftMessageType.MT940)
        execute(
            "update swift_messages set booking_first_due_at = '1900-01-01T00:00:00Z' where id = '$unrelated'",
        )
        assertThat(onContext { query.bookingAgeSnapshot() }.oldestDueAt).isEqualTo(baseline.oldestDueAt)
        SwiftStatus.entries.forEach { status ->
            val id = send()
            execute(
                "update swift_messages set status = '${status.name}', booking_first_due_at = null, " +
                    "booking_clock_covered = null where id = '$id'",
            )
        }
        val eligible = onContext { query.bookingAgeSnapshot() }
        assertThat(eligible.unknownLegacyCount).isEqualTo(baseline.unknownLegacyCount + SwiftStatus.entries.size)
        assertThat(eligible.oldestDueAt).isEqualTo(baseline.oldestDueAt)
    }

    private fun send(
        account: UUID? = UUID.randomUUID(),
        messageType: SwiftMessageType = SwiftMessageType.MT103,
    ): UUID {
        val idempotencyKey = UUID.randomUUID()
        val accountJson = account?.let { "\"$it\"" } ?: "null"
        val body = """
            {"idempotencyKey":"$idempotencyKey","messageType":"${messageType.name}","senderBic":"OPBKCZPP",
            "receiverBic":"DEUTDEFF","transactionReference":"BK-${idempotencyKey.toString().take(10)}",
            "valueDate":"20261022","currency":"EUR","amountMinorUnits":125000,
            "orderingCustomerAccount":"DE89370400440532013000","orderingCustomerAccountId":$accountJson,
            "orderingCustomerName":"Alice","beneficiaryAccount":"GB33BUKB20201555555555",
            "beneficiaryName":"Bob","chargeCode":"SHA","priority":"NORMAL"}
        """.trimIndent()
        val id = UUID.fromString(
            given().contentType("application/json").body(body).post("/api/v1/swift")
                .then().statusCode(201).extract().path("id"),
        )
        synchronized(ids) { ids.add(id) }
        return id
    }

    private fun operator(id: UUID, path: String, field: String) {
        given().contentType("application/json").body(mapOf(field to "controlled"))
            .post("/api/v1/swift/$id/$path").then().statusCode(200)
    }

    private fun storedInstant(id: UUID, column: String): Instant = dataSource.connection.use { connection ->
        connection.prepareStatement("select $column from swift_messages where id = ?").use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { result ->
                check(result.next())
                result.getObject(1, OffsetDateTime::class.java).toInstant()
            }
        }
    }

    private fun row(id: UUID): Map<String, String?> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "select status, booking_first_due_at, booking_proven_at, booking_transaction_id, xmin::text " +
                "from swift_messages where id = ?",
        ).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { result ->
                check(result.next())
                listOf("status", "booking_first_due_at", "booking_proven_at", "booking_transaction_id", "xmin")
                    .associateWith { result.getString(it) }
            }
        }
    }

    private fun completedEvents(id: UUID): Int = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "select count(*) from swift_outbox where aggregate_id = '$id' and payload like '%COMPLETED%'",
            ).use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }

    private fun completedOutboxXmin(id: UUID): String = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "select xmin::text from swift_outbox where aggregate_id = '$id' and payload like '%COMPLETED%'",
            ).use { result ->
                check(result.next())
                result.getString(1)
            }
        }
    }

    private fun completedEvent(message: SwiftMessage) = OutboxMessage(
        aggregateId = message.id,
        eventType = "swift.message.status-changed",
        payload = """{"sourceService":"swift-service","swiftMessageId":"${message.id}","status":"COMPLETED"}""",
    )

    private fun execute(sql: String) {
        dataSource.connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
    }

    companion object {
        private val NANO_NOW = Instant.parse("2026-10-23T12:00:00.123456789Z")

        @Volatile
        private var schemeObservation: (SwiftMessage) -> Unit = {}

        @Volatile
        private var behavior: suspend (SwiftMessage) -> SettlementOutcome = { SettlementOutcome(false, null) }
    }
}
