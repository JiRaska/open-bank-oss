// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.integration

import com.openbank.libs.idempotency.IdempotencyScope
import com.openbank.sepa.application.port.out.SepaPaymentOutboxMessage
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaRejectReason
import com.openbank.sepa.infrastructure.persistence.repository.SepaPaymentRepositoryImpl
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured
import io.restassured.response.Response
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(SepaPaymentOutboxAtomicityIT.NoDispatchInMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.sepa.it.PostgresRedisTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SepaPaymentReceiptIT {
    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var redis: ReactiveRedisDataSource

    @Inject lateinit var paymentRepository: SepaPaymentRepositoryImpl

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    @Test
    @Order(1)
    @TestSecurity(user = "receipt-operator-a", roles = ["ROLE_PAYMENTS"])
    fun `creator can resolve only the original request`() {
        val created = create()
        assertThat(created.statusCode).isEqualTo(201)
        paymentId = created.jsonPath().getString("id")
        assertThat(receipt().jsonPath().getString("paymentId")).isEqualTo(paymentId)
        assertThat(receipt(account = UUID.randomUUID()).jsonPath().getString("state")).isEqualTo("UNKNOWN")
        assertThat(receipt(key = UUID.randomUUID().toString()).jsonPath().getString("state")).isEqualTo("UNKNOWN")
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE sepa_payments SET debtor_name = 'Updated Name', debtor_iban = 'DE89370400440532013000', " +
                    "status = 'PROCESSING' " +
                    "WHERE idempotency_key = ?",
            ).use { statement ->
                statement.setString(1, idempotencyKey)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }
        assertThat(receipt().jsonPath().getString("paymentId")).isEqualTo(paymentId)
        val replay = create()
        assertThat(replay.statusCode).isEqualTo(201)
        assertThat(replay.header("X-Idempotency-Replayed")).isEqualTo("true")
        assertThat(replay.jsonPath().getString("status")).isEqualTo("PROCESSING")
        evictCreatorRedisKey()
        assertThat(receipt().jsonPath().getString("state")).isEqualTo("FOUND")
        assertThat(receipt().jsonPath().getString("paymentId")).isEqualTo(paymentId)
        assertThat(create(requestBody = body.replace("12.34", "12.35")).statusCode).isEqualTo(409)
        assertThat(create().jsonPath().getString("id")).isEqualTo(paymentId)
        assertThat(rows()).isEqualTo(1)
    }

    @Test
    @Order(2)
    @TestSecurity(user = "receipt-operator-b", roles = ["ROLE_PAYMENTS"])
    fun `another principal cannot resolve or replay the globally unique key`() {
        assertThat(receipt().jsonPath().getString("state")).isEqualTo("UNKNOWN")
        assertThat(
            receipt(party = UUID.randomUUID(), actor = UUID.randomUUID()).jsonPath().getString("state"),
        ).isEqualTo("UNKNOWN")
        assertThat(create().statusCode).isEqualTo(409)
        assertThat(rows()).isEqualTo(1)
    }

    @Test
    @Order(3)
    @TestSecurity(user = "receipt-operator-a", roles = ["ROLE_PAYMENTS"])
    fun `legacy row without durable provenance stays unknown and cannot replay`() {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE sepa_payments SET initiating_principal = NULL WHERE idempotency_key = ?",
            ).use { statement ->
                statement.setString(1, idempotencyKey)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }
        assertThat(receipt().jsonPath().getString("state")).isEqualTo("UNKNOWN")
        // A matching Redis record is only a payload hint; it cannot authorize a stale receipt.
        assertThat(create().statusCode).isEqualTo(409)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE sepa_payments SET initiating_principal = ?, request_hash = NULL WHERE idempotency_key = ?",
            ).use { statement ->
                statement.setString(1, "receipt-operator-a")
                statement.setString(2, idempotencyKey)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }
        assertThat(receipt().jsonPath().getString("state")).isEqualTo("UNKNOWN")
        assertThat(create().statusCode).isEqualTo(409)
        evictCreatorRedisKey()
        assertThat(create().statusCode).isEqualTo(409)
    }

    @Test
    @Order(4)
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    fun `edge customers sharing one service principal have separate receipts`() {
        val key = UUID.randomUUID().toString()
        val partyA = UUID.randomUUID()
        val partyB = UUID.randomUUID()
        val actorA = UUID.randomUUID()
        val actorB = UUID.randomUUID()
        assertThat(create(key, partyA, actorA).statusCode).isEqualTo(201)
        val ownerState = receipt(key = key, party = partyA, actor = actorA).jsonPath().getString("state")
        val otherActorState = receipt(key = key, party = partyA, actor = actorB).jsonPath().getString("state")
        assertThat(ownerState).isEqualTo("FOUND")
        assertThat(otherActorState).isEqualTo("UNKNOWN")
        assertThat(create(key, partyA, actorB).statusCode).isEqualTo(409)
        val otherPartyState = receipt(key = key, party = partyB, actor = actorA).jsonPath().getString("state")
        assertThat(otherPartyState).isEqualTo("UNKNOWN")
        assertThat(create(key, partyB, actorA).statusCode).isEqualTo(409)
        assertThat(create(key, partyA).statusCode).isEqualTo(400)
    }

    @Test
    @Order(5)
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    fun `concurrent different actors sharing a key produce one payment and a deliberate conflict`() {
        val key = UUID.randomUUID().toString()
        val party = UUID.randomUUID()
        val actorA = UUID.randomUUID()
        val actorB = UUID.randomUUID()
        // Hold both INSERTs briefly so both HTTP requests can pass their independent Redis
        // reservations and the initial database read before the global UNIQUE index decides.
        installRaceTrigger()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val first = executor.submit<Response> {
                start.await()
                create(key, party, actorA)
            }
            val second = executor.submit<Response> {
                start.await()
                create(key, party, actorB)
            }
            start.countDown()
            val responses = listOf(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))
            assertThat(responses.map { it.statusCode }.sorted())
                .containsExactly(201, 409)
            assertThat(responses.single { it.statusCode == 409 }.jsonPath().getString("code"))
                .isEqualTo("IDEMPOTENCY_KEY_REUSED")
            assertThat(raceAttempts()).isEqualTo(2)
            assertThat(countForKey("SELECT count(*) FROM sepa_payments WHERE idempotency_key = ?", key))
                .isEqualTo(1)
            assertThat(
                countForKey(
                    "SELECT count(*) FROM sepa_payment_outbox o JOIN sepa_payments p " +
                        "ON o.aggregate_id = p.payment_id WHERE p.idempotency_key = ?",
                    key,
                ),
            ).isEqualTo(1)
        } finally {
            executor.shutdownNow()
            removeRaceTrigger()
        }
    }

    @Test
    @Order(6)
    @TestSecurity(user = "receipt-operator-a", roles = ["ROLE_PAYMENTS"])
    fun `committed scheme claim survives a fresh repository call and suppresses the receipt`() {
        val key = UUID.randomUUID().toString()
        val created = create(key)
        assertThat(created.statusCode).isEqualTo(201)
        val id = UUID.fromString(created.jsonPath().getString("id"))
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE sepa_payments SET status = 'VALIDATED' WHERE payment_id = ?").use {
                it.setObject(1, id)
                assertThat(it.executeUpdate()).isEqualTo(1)
            }
        }

        val firstClaim = onEventLoop { paymentRepository.claimSchemeSubmission(id) }
        assertThat(firstClaim?.schemeOutcomeUnknown).isTrue()
        assertThat(onEventLoop { paymentRepository.claimSchemeSubmission(id) }).isNull()
        assertThat(onEventLoop { paymentRepository.findById(id) }?.schemeOutcomeUnknown).isTrue()
        assertThat(receipt(key = key).jsonPath().getString("state")).isEqualTo("UNKNOWN")
        assertThat(
            RestAssured.given().contentType("application/json")
                .body("""{"targetStatus":"REJECTED","rejectReason":"TECHNICAL_ERROR"}""")
                .patch("/api/v1/sepa-payments/$id/status").statusCode,
        ).isEqualTo(409)
        evictCreatorRedisKey(key)
        assertThat(create(key).statusCode).isEqualTo(409)
        assertThat(countForKey("SELECT count(*) FROM sepa_payments WHERE idempotency_key = ?", key)).isEqualTo(1)
    }

    @Test
    @Order(7)
    @TestSecurity(user = "receipt-operator-a", roles = ["ROLE_PAYMENTS"])
    fun `concurrent scheme claims have exactly one winner and old unknown rows cannot be resent`() {
        val key = UUID.randomUUID().toString()
        val created = create(key)
        assertThat(created.statusCode).isEqualTo(201)
        val id = UUID.fromString(created.jsonPath().getString("id"))
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE sepa_payments SET status = 'VALIDATED' WHERE payment_id = ?").use {
                it.setObject(1, id)
                assertThat(it.executeUpdate()).isEqualTo(1)
            }
        }

        val executor = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val attempts = (1..2).map {
                executor.submit<Boolean> {
                    start.await()
                    onEventLoop { paymentRepository.claimSchemeSubmission(id) != null }
                }
            }
            start.countDown()
            assertThat(attempts.map { it.get(20, TimeUnit.SECONDS) }.count { it }).isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }
        assertThat(onEventLoop { paymentRepository.findById(id) }?.schemeOutcomeUnknown).isTrue()
        assertThat(onEventLoop { paymentRepository.claimSchemeSubmission(id) }).isNull()

        // V15's historical VALIDATED backfill has this same state before any new worker starts.
        val historicalKey = UUID.randomUUID().toString()
        val historical = create(historicalKey)
        assertThat(historical.statusCode).isEqualTo(201)
        val historicalId = UUID.fromString(historical.jsonPath().getString("id"))
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE sepa_payments SET status = 'VALIDATED', scheme_outcome_unknown = TRUE WHERE payment_id = ?",
            ).use {
                it.setObject(1, historicalId)
                assertThat(it.executeUpdate()).isEqualTo(1)
            }
        }
        assertThat(onEventLoop { paymentRepository.claimSchemeSubmission(historicalId) }).isNull()
        assertThat(receipt(key = historicalKey).jsonPath().getString("state")).isEqualTo("UNKNOWN")
    }

    @Test
    @Order(8)
    @TestSecurity(user = "receipt-operator-a", roles = ["ROLE_PAYMENTS"])
    fun `scheme claim races a stale status transition without losing the unknown fence`() {
        val key = UUID.randomUUID().toString()
        val created = create(key)
        assertThat(created.statusCode).isEqualTo(201)
        val id = UUID.fromString(created.jsonPath().getString("id"))
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE sepa_payments SET status = 'VALIDATED' WHERE payment_id = ?").use {
                it.setObject(1, id)
                assertThat(it.executeUpdate()).isEqualTo(1)
            }
        }
        val stale = requireNotNull(onEventLoop { paymentRepository.findById(id) })
        val rejected = stale.transitionTo(
            SepaPaymentStatus.REJECTED,
            SepaRejectReason.TECHNICAL_ERROR,
            "test decision",
            Clock.systemUTC(),
        )
        val event = SepaPaymentOutboxMessage(id, "sepa.payment.status-changed", "{}", createdAt = Instant.now())

        val executor = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val claim = executor.submit<Boolean> {
                start.await()
                onEventLoop { paymentRepository.claimSchemeSubmission(id) != null }
            }
            val transition = executor.submit<Boolean> {
                start.await()
                runCatching { onEventLoop { paymentRepository.update(rejected, event) } }.isSuccess
            }
            start.countDown()
            val claimed = claim.get(20, TimeUnit.SECONDS)
            val transitioned = transition.get(20, TimeUnit.SECONDS)
            assertThat(listOf(claimed, transitioned)).containsExactlyInAnyOrder(true, false)
            val current = requireNotNull(onEventLoop { paymentRepository.findById(id) })
            if (claimed) {
                assertThat(current.status).isEqualTo(SepaPaymentStatus.VALIDATED)
                assertThat(current.schemeOutcomeUnknown).isTrue()
                assertThat(current.revision).isEqualTo(stale.revision + 1)
                val afterClaim = current.transitionTo(
                    SepaPaymentStatus.REJECTED,
                    SepaRejectReason.TECHNICAL_ERROR,
                    "post-claim status attempt",
                    Clock.systemUTC(),
                )
                val statusRejected = runCatching { onEventLoop { paymentRepository.update(afterClaim, event) } }
                assertThat(statusRejected.isFailure).isTrue()
            } else {
                assertThat(current.status).isEqualTo(SepaPaymentStatus.REJECTED)
                assertThat(current.schemeOutcomeUnknown).isFalse()
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun installRaceTrigger() = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.execute("CREATE SEQUENCE sepa_receipt_race_attempt_seq START 1")
            statement.execute(
                "CREATE FUNCTION sepa_receipt_race_delay() RETURNS trigger LANGUAGE plpgsql AS " +
                    "'BEGIN PERFORM nextval(''sepa_receipt_race_attempt_seq''); " +
                    "PERFORM pg_sleep(0.5); RETURN NEW; END'",
            )
            statement.execute(
                "CREATE TRIGGER sepa_receipt_race_delay BEFORE INSERT ON sepa_payments " +
                    "FOR EACH ROW EXECUTE FUNCTION sepa_receipt_race_delay()",
            )
        }
    }

    private fun raceAttempts(): Long = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT last_value FROM sepa_receipt_race_attempt_seq").use { result ->
                result.next()
                result.getLong(1)
            }
        }
    }

    private fun removeRaceTrigger() = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.execute("DROP TRIGGER sepa_receipt_race_delay ON sepa_payments")
            statement.execute("DROP FUNCTION sepa_receipt_race_delay()")
            statement.execute("DROP SEQUENCE sepa_receipt_race_attempt_seq")
        }
    }

    private fun countForKey(sql: String, key: String): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }

    private fun create(
        key: String = idempotencyKey,
        party: UUID? = null,
        actor: UUID? = null,
        requestBody: String = body,
    ) = RestAssured.given()
        .contentType("application/json")
        .header("Idempotency-Key", key)
        .apply { if (party != null) header("X-Customer-Party-Id", party.toString()) }
        .apply { if (actor != null) header("X-Customer-Actor-Id", actor.toString()) }
        .body(requestBody)
        .post("/api/v1/sepa-payments")

    private fun receipt(
        key: String = idempotencyKey,
        account: UUID = debtorAccountId,
        party: UUID? = null,
        actor: UUID? = null,
    ) = RestAssured.given()
        .contentType("application/json")
        .apply { if (party != null) header("X-Customer-Party-Id", party.toString()) }
        .apply { if (actor != null) header("X-Customer-Actor-Id", actor.toString()) }
        .body("""{"idempotencyKey":"$key","debtorAccountId":"$account"}""")
        .post("/api/v1/sepa-payments/receipts/lookup")

    private fun rows(): Int = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT count(*) FROM sepa_payments WHERE idempotency_key = ?").use { statement ->
            statement.setString(1, idempotencyKey)
            statement.executeQuery().use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }

    private fun evictCreatorRedisKey(key: String = idempotencyKey) {
        val redisKey = "idempotency:" + IdempotencyScope("sepa-payment", "receipt-operator-a").storeKey(key)
        assertThat(redis.key().del(redisKey).await().indefinitely()).isEqualTo(1)
    }

    private companion object {
        val idempotencyKey = UUID.randomUUID().toString()
        val debtorAccountId = UUID.randomUUID()
        var paymentId: String? = null
        val body = """
            {"type":"SCT","debtorAccountId":"$debtorAccountId",
             "debtorIban":"CZ6508000000192000145399","debtorName":"Alice Example",
             "creditorIban":"DE89370400440532013000","creditorName":"Berlin Utility",
             "creditorBic":"COBADEFFXXX","amount":12.34,"currency":"EUR",
             "remittanceInfo":"Receipt test","endToEndId":null}
        """.trimIndent()
    }
}
