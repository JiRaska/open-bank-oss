// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.integration

import com.openbank.domestic.application.port.out.DelegatedPaymentSaveOutcome
import com.openbank.domestic.application.port.out.DelegatedSpendBindingRepository
import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.application.port.out.ReservationProjectionApplyResult
import com.openbank.domestic.domain.model.DelegatedSpendReservationSnapshot
import com.openbank.domestic.domain.model.DelegatedSpendReservationState
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticTransferScope
import com.openbank.domestic.infrastructure.persistence.repository.DelegatedSpendBindingRepositoryImpl
import com.openbank.domestic.it.PostgresRedisTestResource
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/** Real-Postgres proof of the delegated payment create/finalizer serialization boundary. */
@QuarkusTest
@QuarkusTestResource(DomesticPaymentBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class DelegatedSpendBindingStateMachineIT {
    @Inject
    lateinit var bindingRepository: DelegatedSpendBindingRepository

    @Inject
    lateinit var paymentRepository: DomesticPaymentRepository

    @Inject
    lateinit var dataSource: DataSource

    @BeforeEach
    fun clearBefore() = clearOwnRows()

    @AfterEach
    fun clearAfter() = clearOwnRows()

    @Test
    fun `delegated create commits payment created-outbox and PENDING to BOUND together`() {
        val reservation = reservation()
        onVertxContext { bindingRepository.applySnapshot(reservation) }
        val payment = payment(reservation)

        val result = onVertxContext {
            paymentRepository.saveDelegated(
                payment = payment,
                outboxMessage = createdOutbox(payment),
                boundAt = NOW,
                debitOwnerPartyId = TEST_GRANTOR_ID,
            )
        }

        assertThat(result).isEqualTo(DelegatedPaymentSaveOutcome.Created(payment))
        assertThat(bindingState(reservation.reservationId)).isEqualTo("BOUND")
        assertThat(bindingPaymentId(reservation.reservationId)).isEqualTo(payment.id)
        assertThat(count("domestic_payments", "payment_id", payment.id)).isEqualTo(1)
        assertThat(count("domestic_payment_outbox", "aggregate_id", payment.id)).isEqualTo(1)

        // A terminal source snapshot may arrive after create; it updates source evidence but must
        // never turn a durably BOUND row into an absence tombstone.
        onVertxContext { bindingRepository.applySnapshot(terminal(reservation)) }
        assertThat(bindingState(reservation.reservationId)).isEqualTo("BOUND")
        assertThat(bindingPaymentId(reservation.reservationId)).isEqualTo(payment.id)
    }

    @Test
    fun `terminal revision arriving first creates a permanent tombstone and delayed RESERVED is stale`() {
        val reserved = reservation()
        val terminal = terminal(reserved)

        val terminalApply = onVertxContext { bindingRepository.applySnapshot(terminal) }
        val delayedReservedApply = onVertxContext { bindingRepository.applySnapshot(reserved) }
        val latePayment = payment(reserved)
        val outcome = onVertxContext {
            paymentRepository.saveDelegated(
                payment = latePayment,
                outboxMessage = createdOutbox(latePayment),
                boundAt = NOW,
                debitOwnerPartyId = TEST_GRANTOR_ID,
            )
        }

        assertThat(terminalApply).isEqualTo(ReservationProjectionApplyResult.APPLIED)
        assertThat(delayedReservedApply).isEqualTo(ReservationProjectionApplyResult.STALE_OR_DUPLICATE)
        assertThat(bindingState(reserved.reservationId)).isEqualTo("FINALIZED_ABSENT")
        assertThat(reservationVersion(reserved.reservationId)).isEqualTo(2)
        assertThat(outcome).isEqualTo(DelegatedPaymentSaveOutcome.FinalizedAbsent)
        assertThat(count("domestic_payments", "payment_id", latePayment.id)).isZero()
    }

    @Test
    fun `nanosecond source timestamps survive first delivery and exact replay at Postgres precision`() {
        val reserved = reservation().copy(
            createdAt = Instant.parse("2026-09-01T11:59:00.123456789Z"),
            occurredAt = Instant.parse("2026-09-01T11:59:01.987654321Z"),
        )

        val first = onVertxContext { bindingRepository.applySnapshot(reserved) }
        val duplicate = onVertxContext { bindingRepository.applySnapshot(reserved) }
        val storedReserved = onVertxContext {
            bindingRepository.findByReservationId(reserved.reservationId)
        }!!.snapshot

        assertThat(first).isEqualTo(ReservationProjectionApplyResult.APPLIED)
        assertThat(duplicate).isEqualTo(ReservationProjectionApplyResult.STALE_OR_DUPLICATE)
        assertThat(storedReserved.createdAt).isEqualTo(Instant.parse("2026-09-01T11:59:00.123456Z"))
        assertThat(storedReserved.occurredAt).isEqualTo(Instant.parse("2026-09-01T11:59:01.987654Z"))

        val terminal = terminal(reserved).copy(
            settledAt = Instant.parse("2026-09-01T11:58:59.111222333Z"),
            occurredAt = Instant.parse("2026-09-01T11:58:59.444555666Z"),
        )
        val terminalFirst = onVertxContext { bindingRepository.applySnapshot(terminal) }
        val terminalDuplicate = onVertxContext { bindingRepository.applySnapshot(terminal) }
        val storedTerminal = onVertxContext {
            bindingRepository.findByReservationId(reserved.reservationId)
        }!!.snapshot

        assertThat(terminalFirst).isEqualTo(ReservationProjectionApplyResult.APPLIED)
        assertThat(terminalDuplicate).isEqualTo(ReservationProjectionApplyResult.STALE_OR_DUPLICATE)
        assertThat(storedTerminal.settledAt).isEqualTo(Instant.parse("2026-09-01T11:58:59.111222Z"))
        assertThat(storedTerminal.occurredAt).isEqualTo(Instant.parse("2026-09-01T11:58:59.444555Z"))
    }

    @Test
    fun `outbox write failure rolls payment and BOUND transition back atomically`() {
        val reservation = reservation()
        onVertxContext { bindingRepository.applySnapshot(reservation) }
        val payment = payment(reservation)
        addFinalizedOutboxFault(eventType = CREATE_EVENT_TYPE)
        try {
            assertThatThrownBy {
                onVertxContext {
                    paymentRepository.saveDelegated(
                        payment = payment,
                        outboxMessage = createdOutbox(payment),
                        boundAt = NOW,
                        debitOwnerPartyId = TEST_GRANTOR_ID,
                    )
                }
            }.isInstanceOf(RuntimeException::class.java)
        } finally {
            dropOutboxFault()
        }

        assertThat(bindingState(reservation.reservationId)).isEqualTo("PENDING")
        assertThat(bindingPaymentId(reservation.reservationId)).isNull()
        assertThat(count("domestic_payments", "payment_id", payment.id)).isZero()
        assertThat(count("domestic_payment_outbox", "aggregate_id", payment.id)).isZero()
    }

    @Test
    fun `finalizer outbox failure rolls FINALIZED_ABSENT back to PENDING`() {
        val reservation = reservation()
        onVertxContext { bindingRepository.applySnapshot(reservation) }
        addFinalizedOutboxFault(eventType = DelegatedSpendBindingRepositoryImpl.FINALIZED_ABSENT_OUTBOX_EVENT)
        try {
            assertThatThrownBy {
                onVertxContext { bindingRepository.finalizeAbsentBefore(Instant.now().plusSeconds(60), 1) }
            }.isInstanceOf(RuntimeException::class.java)
        } finally {
            dropOutboxFault()
        }

        assertThat(bindingState(reservation.reservationId)).isEqualTo("PENDING")
        assertThat(count("domestic_payment_outbox", "aggregate_id", reservation.reservationId)).isZero()
    }

    @Test
    fun `create row lock makes concurrent finalizer skip and commits only payment outcome`() {
        val reservation = reservation()
        onVertxContext { bindingRepository.applySnapshot(reservation) }
        val payment = payment(reservation)
        val executor = Executors.newSingleThreadExecutor()
        val advisoryLockHolder = dataSource.connection
        try {
            installCreateBarrier(advisoryLockHolder)
            // The trigger pauses the payment INSERT after saveDelegated already acquired the
            // binding row lock. The finalizer's SKIP LOCKED query must therefore leave the row for
            // create, never emit an absence event, and never wait behind it.
            val create = CompletableFuture.supplyAsync(
                {
                    onVertxContext {
                        paymentRepository.saveDelegated(
                            payment = payment,
                            outboxMessage = createdOutbox(payment),
                            boundAt = NOW,
                            debitOwnerPartyId = TEST_GRANTOR_ID,
                        )
                    }
                },
                executor,
            )
            awaitDatabaseLockWaiter("domestic_payments")

            val finalized = onVertxContext {
                bindingRepository.finalizeAbsentBefore(Instant.now().plusSeconds(60), 1)
            }
            releaseAdvisoryBarrier(advisoryLockHolder, CREATE_BARRIER_LOCK)
            val createResult = create.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            assertThat(createResult).isEqualTo(DelegatedPaymentSaveOutcome.Created(payment))
            assertThat(finalized).isZero()
            assertThat(bindingState(reservation.reservationId)).isEqualTo("BOUND")
            assertThat(bindingPaymentId(reservation.reservationId)).isEqualTo(payment.id)
            assertThat(count("domestic_payments", "payment_id", payment.id)).isEqualTo(1)
            assertThat(count("domestic_payment_outbox", "aggregate_id", payment.id)).isEqualTo(1)
            assertThat(count("domestic_payment_outbox", "aggregate_id", reservation.reservationId)).isZero()
        } finally {
            releaseAdvisoryBarrier(advisoryLockHolder, CREATE_BARRIER_LOCK)
            advisoryLockHolder.close()
            executor.shutdownNow()
            executor.awaitTermination(EXECUTOR_CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            dropCreateBarrier()
        }
    }

    @Test
    fun `finalizer row lock wins deterministic create race without payment or dangling outbox`() {
        val reservation = reservation()
        onVertxContext { bindingRepository.applySnapshot(reservation) }
        val payment = payment(reservation)
        val executor = Executors.newFixedThreadPool(2)
        val advisoryLockHolder = dataSource.connection
        try {
            installFinalizerBarrier(advisoryLockHolder)
            // The trigger pauses the finalizer's outbox INSERT on an advisory lock. At that point
            // its transaction already owns the binding row lock, which gives the create path a
            // deterministic real-Postgres barrier rather than a scheduler-timing race.
            val finalize = CompletableFuture.supplyAsync(
                {
                    onVertxContext {
                        bindingRepository.finalizeAbsentBefore(Instant.now().plusSeconds(60), 1)
                    }
                },
                executor,
            )
            awaitDatabaseLockWaiter("domestic_payment_outbox")

            val createStarted = CountDownLatch(1)
            val create = CompletableFuture.supplyAsync(
                {
                    createStarted.countDown()
                    onVertxContext {
                        paymentRepository.saveDelegated(
                            payment = payment,
                            outboxMessage = createdOutbox(payment),
                            boundAt = NOW,
                            debitOwnerPartyId = TEST_GRANTOR_ID,
                        )
                    }
                },
                executor,
            )
            assertThat(createStarted.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
            awaitDatabaseLockWaiter("domestic_delegated_spend_bindings")

            releaseAdvisoryBarrier(advisoryLockHolder, FINALIZER_BARRIER_LOCK)
            val finalized = finalize.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val createResult = create.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            assertThat(finalized).isEqualTo(1)
            assertThat(createResult).isEqualTo(DelegatedPaymentSaveOutcome.FinalizedAbsent)
            assertThat(bindingState(reservation.reservationId)).isEqualTo("FINALIZED_ABSENT")
            assertThat(bindingPaymentId(reservation.reservationId)).isNull()
            assertThat(count("domestic_payments", "payment_id", payment.id)).isZero()
            assertThat(count("domestic_payment_outbox", "aggregate_id", payment.id)).isZero()
            assertThat(count("domestic_payment_outbox", "aggregate_id", reservation.reservationId)).isEqualTo(1)
        } finally {
            releaseAdvisoryBarrier(advisoryLockHolder, FINALIZER_BARRIER_LOCK)
            advisoryLockHolder.close()
            executor.shutdownNow()
            executor.awaitTermination(EXECUTOR_CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            dropFinalizerBarrier()
        }
    }

    private fun reservation() = DelegatedSpendReservationSnapshot(
        eventId = UUID.randomUUID(),
        reservationId = UUID.randomUUID(),
        delegationId = UUID.randomUUID(),
        grantorPartyId = TEST_GRANTOR_ID,
        granteePartyId = TEST_GRANTEE_ID,
        resourceType = "ACCOUNT",
        resourceId = UUID.randomUUID(),
        amount = BigDecimal("42.50"),
        currency = "CZK",
        idempotencyKeyHash = DelegatedSpendReservationSnapshot.hashIdempotencyKey(TEST_IDEMPOTENCY_KEY),
        operationType = "DOMESTIC_PAYMENT",
        reservationState = DelegatedSpendReservationState.RESERVED,
        reservationVersion = 1,
        schemaVersion = 1,
        aggregateType = "DelegationSpendReservation",
        sourceService = "delegation-service",
        createdAt = NOW.minusSeconds(60),
        settledAt = null,
        occurredAt = NOW.minusSeconds(60),
    )

    private fun terminal(reserved: DelegatedSpendReservationSnapshot) = reserved.copy(
        eventId = UUID.randomUUID(),
        reservationState = DelegatedSpendReservationState.RELEASED,
        reservationVersion = 2,
        // Version is authoritative across pods; producer clock skew is accepted and retained.
        settledAt = reserved.createdAt.minusSeconds(1),
        occurredAt = reserved.createdAt.minusSeconds(1),
    )

    private fun payment(reservation: DelegatedSpendReservationSnapshot) = DomesticPayment(
        id = UUID.randomUUID(),
        idempotencyKey = TEST_IDEMPOTENCY_KEY,
        status = DomesticPaymentStatus.RECEIVED,
        debtorAccountId = reservation.resourceId,
        debtorAccountNumber = "1234567890",
        debtorBankCode = "0800",
        debtorName = "Grantor",
        creditorAccountNumber = "0987654321",
        creditorBankCode = "2010",
        creditorName = "Payee",
        amount = reservation.amount,
        currency = reservation.currency,
        variableSymbol = null,
        specificSymbol = null,
        constantSymbol = null,
        messageForPayee = null,
        priority = DomesticPaymentPriority.STANDARD,
        transferScope = DomesticTransferScope.EXTERNAL,
        technicalAccountCode = null,
        statementLabel = null,
        endToEndId = "DOM-BINDING-IT-${reservation.reservationId}",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = null,
        settledAt = null,
        createdAt = NOW,
        updatedAt = NOW,
        initiatedByPartyId = TEST_GRANTEE_ID,
        requestFingerprint = "b".repeat(64),
        delegationId = reservation.delegationId,
        reservationId = reservation.reservationId,
    )

    private fun createdOutbox(payment: DomesticPayment) = OutboxMessage(
        aggregateId = payment.id,
        eventType = CREATE_EVENT_TYPE,
        payload = "{\"paymentId\":\"${payment.id}\"}",
        createdAt = NOW,
    )

    private fun addFinalizedOutboxFault(eventType: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.execute(
                    "ALTER TABLE domestic_payment_outbox ADD CONSTRAINT $FAULT_CONSTRAINT " +
                        "CHECK (event_type <> '$eventType') NOT VALID",
                )
            }
        }
    }

    private fun dropOutboxFault() {
        dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.execute("ALTER TABLE domestic_payment_outbox DROP CONSTRAINT IF EXISTS $FAULT_CONSTRAINT")
            }
        }
    }

    private fun installFinalizerBarrier(advisoryLockHolder: Connection) {
        dropFinalizerBarrier()
        acquireAdvisoryBarrier(advisoryLockHolder, FINALIZER_BARRIER_LOCK)
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE FUNCTION $FINALIZER_BARRIER_FUNCTION() RETURNS trigger
                    LANGUAGE plpgsql AS ${'$'}${'$'}
                    BEGIN
                        IF NEW.event_type = '${DelegatedSpendBindingRepositoryImpl.FINALIZED_ABSENT_OUTBOX_EVENT}' THEN
                            PERFORM pg_advisory_xact_lock($FINALIZER_BARRIER_LOCK);
                        END IF;
                        RETURN NEW;
                    END
                    ${'$'}${'$'}
                    """.trimIndent(),
                )
                statement.execute(
                    "CREATE TRIGGER $FINALIZER_BARRIER_TRIGGER BEFORE INSERT ON domestic_payment_outbox " +
                        "FOR EACH ROW EXECUTE FUNCTION $FINALIZER_BARRIER_FUNCTION()",
                )
            }
        }
    }

    private fun installCreateBarrier(advisoryLockHolder: Connection) {
        dropCreateBarrier()
        acquireAdvisoryBarrier(advisoryLockHolder, CREATE_BARRIER_LOCK)
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE FUNCTION $CREATE_BARRIER_FUNCTION() RETURNS trigger
                    LANGUAGE plpgsql AS ${'$'}${'$'}
                    BEGIN
                        IF NEW.reservation_id IS NOT NULL THEN
                            PERFORM pg_advisory_xact_lock($CREATE_BARRIER_LOCK);
                        END IF;
                        RETURN NEW;
                    END
                    ${'$'}${'$'}
                    """.trimIndent(),
                )
                statement.execute(
                    "CREATE TRIGGER $CREATE_BARRIER_TRIGGER BEFORE INSERT ON domestic_payments " +
                        "FOR EACH ROW EXECUTE FUNCTION $CREATE_BARRIER_FUNCTION()",
                )
            }
        }
    }

    private fun acquireAdvisoryBarrier(advisoryLockHolder: Connection, lockId: Long) {
        advisoryLockHolder.prepareStatement("SELECT pg_advisory_lock(?)").use { statement ->
            statement.setLong(1, lockId)
            statement.executeQuery().use { rows -> assertThat(rows.next()).isTrue() }
        }
    }

    private fun releaseAdvisoryBarrier(advisoryLockHolder: Connection, lockId: Long) {
        if (advisoryLockHolder.isClosed) return
        advisoryLockHolder.prepareStatement("SELECT pg_advisory_unlock(?)").use { statement ->
            statement.setLong(1, lockId)
            statement.executeQuery().use { rows -> assertThat(rows.next()).isTrue() }
        }
    }

    private fun dropFinalizerBarrier() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "DROP TRIGGER IF EXISTS $FINALIZER_BARRIER_TRIGGER ON domestic_payment_outbox",
                )
                statement.execute("DROP FUNCTION IF EXISTS $FINALIZER_BARRIER_FUNCTION()")
            }
        }
    }

    private fun dropCreateBarrier() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TRIGGER IF EXISTS $CREATE_BARRIER_TRIGGER ON domestic_payments")
                statement.execute("DROP FUNCTION IF EXISTS $CREATE_BARRIER_FUNCTION()")
            }
        }
    }

    private fun awaitDatabaseLockWaiter(queryFragment: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(RACE_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (countDatabaseLockWaiters(queryFragment) > 0) return
            Thread.sleep(LOCK_POLL_INTERVAL_MILLIS)
        }
        throw AssertionError("Timed out waiting for database lock waiter containing '$queryFragment'")
    }

    private fun countDatabaseLockWaiters(queryFragment: String): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT COUNT(*)
            FROM pg_stat_activity
            WHERE datname = current_database()
              AND pid <> pg_backend_pid()
              AND wait_event_type = 'Lock'
              AND query ILIKE ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, "%$queryFragment%")
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getInt(1)
            }
        }
    }

    private fun bindingState(reservationId: UUID): String = queryOne(
        "SELECT binding_state FROM domestic_delegated_spend_bindings WHERE reservation_id = ?",
        reservationId,
    ) { it.getString(1) }

    private fun bindingPaymentId(reservationId: UUID): UUID? = queryOne(
        "SELECT payment_id FROM domestic_delegated_spend_bindings WHERE reservation_id = ?",
        reservationId,
    ) { it.getObject(1, UUID::class.java) }

    private fun reservationVersion(reservationId: UUID): Long = queryOne(
        "SELECT reservation_version FROM domestic_delegated_spend_bindings WHERE reservation_id = ?",
        reservationId,
    ) { it.getLong(1) }

    private fun count(table: String, column: String, id: UUID): Int = queryOne(
        "SELECT COUNT(*) FROM $table WHERE $column = ?",
        id,
    ) { it.getInt(1) }

    private fun <T> queryOne(sql: String, id: UUID, mapper: (java.sql.ResultSet) -> T): T =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    mapper(rows)
                }
            }
        }

    private fun clearOwnRows() {
        dropOutboxFault()
        dropFinalizerBarrier()
        dropCreateBarrier()
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { it.execute("SET CONSTRAINTS ALL DEFERRED") }
            connection.prepareStatement(
                "DELETE FROM domestic_payment_outbox WHERE aggregate_id IN " +
                    "(SELECT reservation_id FROM domestic_delegated_spend_bindings WHERE grantor_party_id = ?) " +
                    "OR aggregate_id IN (SELECT payment_id FROM domestic_payments WHERE reservation_id IN " +
                    "(SELECT reservation_id FROM domestic_delegated_spend_bindings WHERE grantor_party_id = ?))",
            ).use { statement ->
                statement.setObject(1, TEST_GRANTOR_ID)
                statement.setObject(2, TEST_GRANTOR_ID)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "DELETE FROM domestic_payments WHERE reservation_id IN " +
                    "(SELECT reservation_id FROM domestic_delegated_spend_bindings WHERE grantor_party_id = ?)",
            ).use { statement ->
                statement.setObject(1, TEST_GRANTOR_ID)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "DELETE FROM domestic_delegated_spend_bindings WHERE grantor_party_id = ?",
            ).use { statement ->
                statement.setObject(1, TEST_GRANTOR_ID)
                statement.executeUpdate()
            }
            connection.commit()
        }
    }

    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        uni(CoroutineScope(Dispatchers.Unconfined)) { block() }
    }

    private companion object {
        const val TEST_IDEMPOTENCY_KEY = "binding-state-it-payment"
        const val CREATE_EVENT_TYPE = "domestic.payment.created"
        const val FAULT_CONSTRAINT = "chk_binding_state_it_outbox_fault"
        const val FINALIZER_BARRIER_FUNCTION = "binding_state_it_finalizer_barrier"
        const val FINALIZER_BARRIER_TRIGGER = "binding_state_it_finalizer_barrier_trigger"
        const val FINALIZER_BARRIER_LOCK = 6_002_026_090_001L
        const val CREATE_BARRIER_FUNCTION = "binding_state_it_create_barrier"
        const val CREATE_BARRIER_TRIGGER = "binding_state_it_create_barrier_trigger"
        const val CREATE_BARRIER_LOCK = 6_002_026_090_002L
        const val RACE_TIMEOUT_SECONDS = 120L
        const val EXECUTOR_CLEANUP_TIMEOUT_SECONDS = 5L
        const val LOCK_POLL_INTERVAL_MILLIS = 25L
        val NOW: Instant = Instant.parse("2026-09-01T12:00:00Z")
        val TEST_GRANTOR_ID: UUID = UUID.fromString("60000000-0000-4000-8000-000000000001")
        val TEST_GRANTEE_ID: UUID = UUID.fromString("60000000-0000-4000-8000-000000000002")
    }
}
