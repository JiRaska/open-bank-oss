// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.integration

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.openbank.libs.domain.money.Money
import com.openbank.libs.market.BookingContext
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.synthetic.SyntheticTaint
import com.openbank.transaction.application.port.`in`.ReverseTransactionCommand
import com.openbank.transaction.application.port.out.TransactionRepository
import com.openbank.transaction.application.usecase.BranchReversalService
import com.openbank.transaction.application.usecase.TransactionUpdateConflictException
import com.openbank.transaction.application.workflow.BranchPaymentDispatch
import com.openbank.transaction.domain.model.Transaction
import com.openbank.transaction.domain.model.TransactionStatus
import com.openbank.transaction.domain.model.TransactionType
import com.openbank.transaction.it.PostgresRedpandaTestResource
import io.mockk.coEvery
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@QuarkusTest
@io.quarkus.test.junit.TestProfile(BranchPaymentRecoveryIT.RecoveryProfile::class)
@QuarkusTestResource(PostgresRedpandaTestResource::class)
@QuarkusTestResource(LedgerWireMockResource::class)
class BranchReversalRecoveryIT {
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbc: String

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.datasource.username")
    lateinit var databaseUser: String

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.datasource.password")
    lateinit var databasePassword: String

    @Inject
    lateinit var transactionRepository: TransactionRepository

    @Inject
    lateinit var events: com.openbank.transaction.application.port.out.TransactionEventPublisher

    private companion object {
        const val COMPLETED_EVENT = "openbank.transactions.transaction.completed"
    }

    @ParameterizedTest
    @ValueSource(strings = ["TRANSFER", "DEBIT", "CREDIT", "SAME_BOOK"])
    @io.quarkus.test.security.TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `HTTP branch reversals retain the committed route with unavailable current accounts`(route: String) {
        val original = completedBranchTransfer(route)
        val key = "http-reverse-${UUID.randomUUID()}"
        val opposite = reversalIntent(original)
        val count = if (route == "TRANSFER") 2 else 1
        val journals = (1..count).map { UUID.randomUUID().toString() }
        val bank = (original.sourceBookingContext ?: original.targetBookingContext)!!.bankId
        val ids = journals.joinToString(",") { "\"$it\"" }
        fun accountMatcher(id: UUID?) = id?.let { equalTo(it.toString()) } ?: absent()
        LedgerWireMockResource.server.stubFor(
            post(urlEqualTo("/api/v1/journals/branch-payments"))
                .withRequestBody(matchingJsonPath("$.sourceAccountId", accountMatcher(opposite.sourceAccountId)))
                .withRequestBody(matchingJsonPath("$.targetAccountId", accountMatcher(opposite.targetAccountId)))
                .willReturn(
                    okJson(
                        """{"transactionId":"{{jsonPath request.body '$.transactionId'}}","bankId":"$bank","journalIds":[$ids]}""",
                    ).withTransformers("response-template"),
                ),
        )
        val accounts = listOfNotNull(original.sourceAccountId, original.targetAccountId)
        accounts.forEach { id ->
            LedgerWireMockResource.server.stubFor(
                get(urlEqualTo("/api/v1/accounts/$id"))
                    .willReturn(aResponse().withStatus(503)),
            )
        }
        fun reverse(id: UUID) = given().contentType("application/json")
            .body(mapOf("idempotencyKey" to key, "reason" to "branch return"))
            .post("/api/v1/transactions/$id/reverse")
        val response = reverse(original.id)
        assertThat(response.statusCode).isEqualTo(200)
        val id = UUID.fromString(response.jsonPath().getString("id"))
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(30)).untilAsserted {
            assertThat(onVertxContext { transactionRepository.findById(id) }!!.status)
                .isEqualTo(TransactionStatus.COMPLETED)
        }
        val reversal = onVertxContext { transactionRepository.findById(id) }!!
        assertThat(reversal.sourceAccountId).isEqualTo(original.targetAccountId)
        assertThat(reversal.targetAccountId).isEqualTo(original.sourceAccountId)
        assertThat(reversal.sourceBookingContext).isEqualTo(original.targetBookingContext)
        assertThat(reversal.targetBookingContext).isEqualTo(original.sourceBookingContext)
        assertThat(reverse(original.id).jsonPath().getString("id")).isEqualTo(id.toString())
        val other = completedBranchTransfer()
        assertThat(reverse(other.id).statusCode).isEqualTo(422)
        assertThat(onVertxContext { transactionRepository.findById(other.id) }!!.status)
            .isEqualTo(TransactionStatus.COMPLETED)
        assertReversalCounts(original.id, id)
        accounts.forEach { account ->
            LedgerWireMockResource.server.verify(
                0,
                getRequestedFor(
                    urlEqualTo("/api/v1/accounts/$account"),
                ),
            )
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `branch reversal intent survives caller loss and resumes with the original opposite books`(synthetic: Boolean) {
        val original = completedBranchTransfer(synthetic = synthetic)
        val key = "branch-reversal-${UUID.randomUUID()}"
        val dispatch = mockk<BranchPaymentDispatch>()
        coEvery { dispatch.start(any()) } throws IllegalStateException("Injected loss before Temporal start")
        val service = BranchReversalService(
            transactionRepository,
            events,
            dispatch,
        )
        val failure = runCatching {
            onVertxContext {
                service.reverse(
                    original,
                    ReverseTransactionCommand(
                        original.id,
                        key,
                        "return",
                    ),
                )
            }
        }.exceptionOrNull()
        assertThat(failure).hasMessage("Injected loss before Temporal start")
        val pending = onVertxContext { transactionRepository.findByIdempotencyKey(key) }!!
        assertThat(pending.status).isEqualTo(TransactionStatus.PENDING)
        assertThat(pending.reversalOf).isEqualTo(original.id)
        assertThat(pending.sourceBookingContext).isEqualTo(original.targetBookingContext)
        assertThat(pending.targetBookingContext).isEqualTo(original.sourceBookingContext)
        assertThat(onVertxContext { transactionRepository.findById(original.id) }!!.status)
            .isEqualTo(TransactionStatus.REVERSED)
        stubBranchReceipt(pending)
        java.sql.DriverManager.getConnection(jdbc, databaseUser, databasePassword).use { db ->
            db.createStatement().use {
                it.execute("UPDATE transactions SET initiated_at=now()-interval '2 minutes' WHERE id='${pending.id}'")
            }
        }
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(30)).untilAsserted {
            assertThat(onVertxContext { transactionRepository.findById(pending.id) }!!.status)
                .isEqualTo(TransactionStatus.COMPLETED)
        }
        val completed = onVertxContext { transactionRepository.findById(pending.id) }!!
        assertThat(completed.sourceBookingContext).isEqualTo(original.targetBookingContext)
        assertThat(completed.targetBookingContext).isEqualTo(original.sourceBookingContext)
        assertReversalCounts(original.id, pending.id, synthetic = synthetic)
        LedgerWireMockResource.server.verify(
            0,
            getRequestedFor(
                urlEqualTo("/api/v1/accounts/${original.sourceAccountId}"),
            ),
        )
        assertOppositeLedgerRoute(original, pending)
    }

    @org.junit.jupiter.api.Test
    fun `failed reversal outbox write rolls back the original status and the pending intent`() {
        val original = completedBranchTransfer()
        val reversal = reversalIntent(original)
        val duplicateEvent = UUID.randomUUID()
        val failure = runCatching {
            onVertxContext {
                transactionRepository.saveBranchReversal(
                    original.reverse(),
                    reversal,
                    OutboxMessage(
                        eventId = duplicateEvent,
                        aggregateId = original.id,
                        eventType = "reversed",
                        payload = "{}",
                    ),
                    OutboxMessage(
                        eventId = duplicateEvent,
                        aggregateId = reversal.id,
                        eventType = "initiated",
                        payload = "{}",
                    ),
                )
            }
        }.exceptionOrNull()
        assertThat(failure).hasStackTraceContaining("transaction_outbox_event_id_key")
        assertThat(onVertxContext { transactionRepository.findById(original.id) }!!.status)
            .isEqualTo(TransactionStatus.COMPLETED)
        assertThat(onVertxContext { transactionRepository.findById(reversal.id) }).isNull()
        assertThat(onVertxContext { transactionRepository.findByIdempotencyKey(reversal.idempotencyKey) }).isNull()
        java.sql.DriverManager.getConnection(jdbc, databaseUser, databasePassword).use { db ->
            db.createStatement().use {
                it.executeQuery(
                    "SELECT count(*) FROM transaction_outbox WHERE event_id='$duplicateEvent'",
                ).use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getLong(1)).isZero()
                }
            }
        }
    }

    @org.junit.jupiter.api.Test
    fun `concurrent distinct branch reversal keys commit only one intent and one original event`() {
        val original = completedBranchTransfer()
        val dispatch = mockk<BranchPaymentDispatch>()
        coEvery { dispatch.start(any()) } returns Unit
        val service = BranchReversalService(
            transactionRepository,
            events,
            dispatch,
        )
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val ready = java.util.concurrent.CountDownLatch(2)
        val start = java.util.concurrent.CountDownLatch(1)
        try {
            val attempts = (1..2).map {
                pool.submit<Result<Transaction>> {
                    ready.countDown()
                    check(start.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    runCatching {
                        onVertxContext {
                            service.reverse(
                                original,
                                ReverseTransactionCommand(
                                    original.id,
                                    "race-${UUID.randomUUID()}",
                                    "return",
                                ),
                            )
                        }
                    }
                }
            }
            check(ready.await(10, java.util.concurrent.TimeUnit.SECONDS))
            start.countDown()
            val results = attempts.map { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
            assertThat(results.count { it.isSuccess }).isEqualTo(1)
            assertThat(results.single { it.isFailure }.exceptionOrNull())
                .isInstanceOf(
                    TransactionUpdateConflictException::class.java,
                )
            val reversal = results.single { it.isSuccess }.getOrThrow()
            assertReversalCounts(original.id, reversal.id, completed = false)
            onVertxContext {
                transactionRepository.update(
                    reversal.fail("test dispatcher intentionally stopped", java.time.Clock.systemUTC()),
                )
            }
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }

    private fun assertOppositeLedgerRoute(original: Transaction, pending: Transaction) {
        LedgerWireMockResource.server.verify(
            postRequestedFor(urlEqualTo(branchPath(pending)))
                .withRequestBody(matchingJsonPath("$.transactionId", equalTo(pending.id.toString())))
                .withHeader(
                    SyntheticTaint.KAFKA_HEADER,
                    if (pending.synthetic) equalTo(SyntheticTaint.TRUE_VALUE) else absent(),
                )
                .withRequestBody(
                    matchingJsonPath(
                        "$.sourceBookId",
                        equalTo(original.targetBookingContext!!.establishmentId.toString()),
                    ),
                )
                .withRequestBody(
                    matchingJsonPath(
                        "$.targetBookId",
                        equalTo(original.sourceBookingContext!!.establishmentId.toString()),
                    ),
                ),
        )
    }

    private fun completedBranchTransfer(route: String = "TRANSFER", synthetic: Boolean = false): Transaction {
        val pending = seedPendingTransfer(synthetic, route)
        return onVertxContext { transactionRepository.update(pending.complete(java.time.Clock.systemUTC())) }
    }

    private fun reversalIntent(original: Transaction): Transaction = original.copy(
        id = UUID.randomUUID(), referenceNumber = "TXN${UUID.randomUUID()}",
        type = TransactionType.REVERSAL, status = TransactionStatus.PENDING, version = 0,
        idempotencyKey = "reverse-${UUID.randomUUID()}", completedAt = null,
        sourceAccountId = original.targetAccountId, targetAccountId = original.sourceAccountId,
        sourceBookingContext = original.targetBookingContext, targetBookingContext = original.sourceBookingContext,
        reversalOf = original.id, isReversal = true,
    )

    private fun branchPath(transaction: Transaction) =
        "/api/v1/journals/branch-payments" + if (transaction.synthetic) "/synthetic" else ""

    private fun stubBranchReceipt(transaction: Transaction) {
        LedgerWireMockResource.server.stubFor(
            post(urlEqualTo(branchPath(transaction)))
                .withRequestBody(matchingJsonPath("$.transactionId", equalTo(transaction.id.toString())))
                .willReturn(
                    okJson(
                        """{"transactionId":"${transaction.id}","bankId":"${transaction.sourceBookingContext!!.bankId}","journalIds":["${UUID.randomUUID()}","${UUID.randomUUID()}"]}""",
                    ),
                ),
        )
    }

    private fun assertReversalCounts(
        original: UUID,
        reversal: UUID,
        completed: Boolean = true,
        synthetic: Boolean = false,
    ) {
        java.sql.DriverManager.getConnection(jdbc, databaseUser, databasePassword).use { db ->
            fun count(sql: String): Long = db.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    check(rows.next())
                    rows.getLong(1)
                }
            }
            assertThat(count("SELECT count(*) FROM transactions WHERE reversal_of='$original'")).isEqualTo(1)
            assertThat(
                count(
                    "SELECT count(*) FROM transactions WHERE id IN ('$original','$reversal') AND synthetic=$synthetic",
                ),
            ).isEqualTo(2)
            assertThat(
                count(
                    "SELECT count(*) FROM transaction_outbox WHERE aggregate_id IN ('$original','$reversal') AND synthetic<>$synthetic",
                ),
            ).isZero()
            assertThat(
                count(
                    "SELECT count(*) FROM transaction_outbox WHERE aggregate_id='$original' AND event_type='openbank.transactions.transaction.reversed'",
                ),
            ).isEqualTo(1)
            assertThat(
                count(
                    "SELECT count(*) FROM transaction_outbox WHERE aggregate_id='$reversal' AND event_type='openbank.transactions.transaction.initiated'",
                ),
            ).isEqualTo(1)
            if (completed) {
                assertThat(
                    count(
                        "SELECT count(*) FROM transaction_outbox WHERE aggregate_id='$reversal' AND event_type='$COMPLETED_EVENT'",
                    ),
                ).isEqualTo(1)
            }
        }
    }

    private fun seedPendingTransfer(synthetic: Boolean, route: String = "TRANSFER"): Transaction {
        val today = LocalDate.now()
        val bank = UUID.randomUUID()
        val transaction = Transaction(
            id = UUID.randomUUID(),
            referenceNumber = "TXN${System.nanoTime()}",
            type = TransactionType.TRANSFER,
            sourceAccountId = UUID.randomUUID(),
            targetAccountId = UUID.randomUUID(),
            amount = Money.of(BigDecimal("700.00"), "CZK"),
            fxRate = null,
            baseAmount = Money.of(BigDecimal("700.00"), "CZK"),
            status = TransactionStatus.PENDING,
            description = "Terminal-write IT",
            valueDate = today,
            bookingDate = today,
            initiatedAt = Instant.now(),
            completedAt = null,
            failedAt = null,
            failureReason = null,
            idempotencyKey = "branch-recovery-it-${UUID.randomUUID()}",
            version = 0L,
            synthetic = synthetic,
            sourceBookingContext = BookingContext(bank, UUID.randomUUID(), "AT", "BRANCH"),
            targetBookingContext = BookingContext(bank, UUID.randomUUID(), "SK", "BRANCH"),
        )
        val routed = when (route) {
            "DEBIT" -> transaction.copy(
                type = TransactionType.DEBIT,
                targetAccountId = null,
                targetBookingContext = null,
            )
            "CREDIT" -> transaction.copy(
                type = TransactionType.CREDIT,
                sourceAccountId = null,
                sourceBookingContext = null,
            )
            "SAME_BOOK" -> transaction.copy(targetBookingContext = transaction.sourceBookingContext)
            else -> transaction
        }
        return onVertxContext {
            transactionRepository.save(
                transaction = routed,
                outboxMessage = OutboxMessage(
                    aggregateId = transaction.id,
                    eventType = "openbank.transactions.transaction.initiated",
                    payload = """{"transactionId":"${transaction.id}"}""",
                    synthetic = synthetic,
                ),
            )
        }
    }

    private fun <T> onVertxContext(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }
}
