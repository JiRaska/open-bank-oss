// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.integration

import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.openbank.libs.domain.money.Money
import com.openbank.libs.market.BookingContext
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.synthetic.SyntheticTaint
import com.openbank.transaction.application.port.out.TransactionRepository
import com.openbank.transaction.application.workflow.PaymentActivities
import com.openbank.transaction.application.workflow.PaymentActivitiesImpl
import com.openbank.transaction.application.workflow.PaymentWorkflowImpl
import com.openbank.transaction.domain.model.Transaction
import com.openbank.transaction.domain.model.TransactionStatus
import com.openbank.transaction.domain.model.TransactionType
import com.openbank.transaction.domain.saga.SagaState
import com.openbank.transaction.infrastructure.persistence.repository.TransactionOutboxRepositoryImpl
import com.openbank.transaction.it.PostgresRedpandaTestResource
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowOptions
import io.temporal.testing.TestWorkflowEnvironment
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Regression coverage for #4238: a settled transaction stayed PENDING forever because the terminal
 * status write lived in `TransactionService`, AFTER the blocking `stub.execute(...)` returned —
 * outside the durable unit. A pod eviction, rollout or client disconnect in that window left the
 * money moved (hold placed, journal posted, balances updated), the Temporal workflow COMPLETED, and
 * the row PENDING with no `...transaction.completed` outbox event, with nothing anywhere to retry.
 *
 * The test therefore runs the REAL [PaymentWorkflowImpl] against the REAL [PaymentActivitiesImpl]
 * CDI bean and then **stops** — it never runs a single line of the caller-side code that used to do
 * the write. What it asserts is exactly the property the fix introduces: *when the workflow returns,
 * the terminal state is already committed*.
 *
 * Falsification: against `origin/main` (terminal write in `TransactionService`) this fails with
 * `expected COMPLETED but was PENDING`, and it passes with the write moved into the workflow's
 * `markCompleted` activity. It compiles against both, so the failure is a real assertion failure and
 * not a build error.
 *
 * Mechanics notes:
 * - The reactive Panache repository cannot be driven from a bare @QuarkusTest thread ("No current
 *   Vertx context found"), so every DB touch goes through [onVertxContext] — the same
 *   `VertxContextSupport` bridge `TransactionOutboxClaimIT` and the activities themselves use.
 * - The cover hold and journal HTTP calls are isolated in [LedgerWireMockResource].
 * - Its own [TestWorkflowEnvironment] — not the one `WorkflowClientTestProducer` publishes, which
 *   registers a stub workflow — because the point of this test is the real workflow's own steps.
 */
@QuarkusTest
@io.quarkus.test.junit.TestProfile(BranchPaymentRecoveryIT.RecoveryProfile::class)
@QuarkusTestResource(PostgresRedpandaTestResource::class)
@QuarkusTestResource(LedgerWireMockResource::class)
class BranchPaymentRecoveryIT {
    class RecoveryProfile : io.quarkus.test.junit.QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "openbank.transaction.branch-recovery.enabled" to "true",
            "openbank.transaction.branch-recovery.interval" to "PT1S",
        )
    }

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbc: String

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.datasource.username")
    lateinit var databaseUser: String

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.datasource.password")
    lateinit var databasePassword: String

    @Inject
    lateinit var transactionRepository: TransactionRepository

    @Inject
    lateinit var activities: PaymentActivitiesImpl

    @Inject
    lateinit var outboxRepository: TransactionOutboxRepositoryImpl

    private lateinit var env: TestWorkflowEnvironment

    private companion object {
        const val TASK_QUEUE = "branch-recovery-it"
        const val COMPLETED_EVENT = "openbank.transactions.transaction.completed"
    }

    @BeforeEach
    fun setUp() {
        env = TestWorkflowEnvironment.newInstance()
        val worker = env.newWorker(TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(
            com.openbank.transaction.application.workflow.BranchPaymentWorkflowImpl::class.java,
        )
        val lostAcknowledgement = java.util.concurrent.atomic.AtomicInteger(4)
        worker.registerActivitiesImplementations(object : PaymentActivities by activities {
            override fun postJournal(transactionId: UUID): UUID {
                val journal = activities.postJournal(transactionId)
                if (lostAcknowledgement.getAndDecrement() > 0) error("Injected worker loss after posted receipt")
                return journal
            }
        })
        env.start()
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    @org.junit.jupiter.api.Test
    @io.quarkus.test.security.TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `HTTP ingestion obtains the account-owned route and commits it before async settlement`() {
        val source = UUID.randomUUID()
        val target = UUID.randomUUID()
        val bank = UUID.randomUUID()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        fun account(id: UUID, book: UUID, country: String) {
            LedgerWireMockResource.server.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/api/v1/accounts/$id"))
                    .willReturn(
                        com.github.tomakehurst.wiremock.client.WireMock.okJson(
                            """{"id":"$id","bookingContext":{"bankId":"$bank","establishmentId":"$book","hostCountry":"$country","mode":"BRANCH"}}""",
                        ),
                    ),
            )
        }
        account(source, first, "AT")
        account(target, second, "SK")
        LedgerWireMockResource.server.stubFor(
            com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/api/v1/journals/branch-payments"))
                .withRequestBody(matchingJsonPath("$.sourceAccountId", equalTo(source.toString())))
                .willReturn(
                    com.github.tomakehurst.wiremock.client.WireMock.okJson(
                        """{"transactionId":"{{jsonPath request.body '$.transactionId'}}","bankId":"$bank","journalIds":["${UUID.randomUUID()}","${UUID.randomUUID()}"]}""",
                    ).withTransformers("response-template"),
                ),
        )
        val response = io.restassured.RestAssured.given().contentType("application/json").body(
            mapOf(
                "idempotencyKey" to "branch-ingress-${UUID.randomUUID()}",
                "type" to "TRANSFER",
                "sourceAccountId" to source,
                "targetAccountId" to target,
                "amount" to "100.00",
                "currencyCode" to "EUR",
                "valueDate" to LocalDate.now().toString(),
            ),
        ).post("/api/v1/transactions")
        assertThat(response.statusCode).isEqualTo(201)
        assertThat(response.jsonPath().getString("sourceBookingContext.establishmentId")).isEqualTo(first.toString())
        assertThat(response.jsonPath().getString("targetBookingContext.establishmentId")).isEqualTo(second.toString())
        val id = UUID.fromString(response.jsonPath().getString("id"))
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(30)).untilAsserted {
            assertThat(
                onVertxContext {
                    transactionRepository.findById(id)
                }!!.status,
            ).isEqualTo(TransactionStatus.COMPLETED)
        }
        val booked = onVertxContext { transactionRepository.findById(id) }!!
        assertThat(booked.sourceBookingContext).isEqualTo(BookingContext(bank, first, "AT", "BRANCH"))
        assertThat(booked.targetBookingContext).isEqualTo(BookingContext(bank, second, "SK", "BRANCH"))
    }

    @org.junit.jupiter.api.Test
    fun `real scheduler starts a committed branch payment after caller loss before workflow start`() {
        val seeded = seedPendingTransfer(false)
        val journals = listOf(UUID.randomUUID(), UUID.randomUUID())
        LedgerWireMockResource.server.stubFor(
            com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/api/v1/journals/branch-payments"))
                .withRequestBody(matchingJsonPath("$.transactionId", equalTo(seeded.id.toString())))
                .willReturn(
                    com.github.tomakehurst.wiremock.client.WireMock.okJson(
                        """{"transactionId":"${seeded.id}","bankId":"${seeded.sourceBookingContext!!.bankId}","journalIds":["${journals[0]}","${journals[1]}"]}""",
                    ),
                ),
        )
        java.sql.DriverManager.getConnection(jdbc, databaseUser, databasePassword).use { db ->
            db.createStatement().use {
                it.execute("UPDATE transactions SET initiated_at=now()-interval '2 minutes' WHERE id='${seeded.id}'")
            }
            val changed = runCatching {
                db.createStatement().use {
                    it.execute(
                        "UPDATE transactions SET source_booking_establishment_id='${UUID.randomUUID()}' WHERE id='${seeded.id}'",
                    )
                }
            }.exceptionOrNull()
            assertThat(changed).isInstanceOf(java.sql.SQLException::class.java)
        }
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(30)).untilAsserted {
            assertThat(onVertxContext { transactionRepository.findById(seeded.id) }!!.status)
                .isEqualTo(TransactionStatus.COMPLETED)
        }
        val events = onVertxContext {
            Panache.withSession {
                outboxRepository.find("aggregateId = ?1 and eventType = ?2", seeded.id, COMPLETED_EVENT).list()
            }
                .awaitSuspending()
        }
        assertThat(events).hasSize(1)
        LedgerWireMockResource.server.verify(
            1,
            postRequestedFor(urlEqualTo("/api/v1/journals/branch-payments"))
                .withRequestBody(matchingJsonPath("$.transactionId", equalTo(seeded.id.toString()))),
        )
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `worker recovery reuses persisted branch route without another client request`(synthetic: Boolean) {
        val seeded = seedPendingTransfer(synthetic)
        val branchPath = if (synthetic) {
            "/api/v1/journals/branch-payments/synthetic"
        } else {
            "/api/v1/journals/branch-payments"
        }
        val journals = listOf(UUID.randomUUID(), UUID.randomUUID())
        LedgerWireMockResource.server.stubFor(
            com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(branchPath))
                .withRequestBody(matchingJsonPath("$.transactionId", equalTo(seeded.id.toString())))
                .willReturn(
                    com.github.tomakehurst.wiremock.client.WireMock.okJson(
                        """{"transactionId":"${seeded.id}","bankId":"${seeded.sourceBookingContext!!.bankId}","journalIds":["${journals[0]}","${journals[1]}"]}""",
                    ),
                ),
        )
        assertThat(newWorkflow(seeded.id).execute(seeded.id)).isEqualTo(SagaState.COMPLETED)
        val reloaded = onVertxContext { transactionRepository.findById(seeded.id) }!!
        assertThat(reloaded.status).isEqualTo(TransactionStatus.COMPLETED)
        assertThat(reloaded.sourceBookingContext).isEqualTo(seeded.sourceBookingContext)
        assertThat(reloaded.targetBookingContext).isEqualTo(seeded.targetBookingContext)
        val events = onVertxContext {
            Panache.withSession {
                outboxRepository.find("aggregateId = ?1 and eventType = ?2", seeded.id, COMPLETED_EVENT).list()
            }.awaitSuspending()
        }
        assertThat(events).hasSize(1)
        assertThat(events.single().payload).contains(
            seeded.sourceBookingContext.establishmentId.toString(),
            seeded.targetBookingContext!!.establishmentId.toString(),
        )
        val request = postRequestedFor(urlEqualTo(branchPath))
            .withRequestBody(matchingJsonPath("$.transactionId", equalTo(seeded.id.toString())))
            .withRequestBody(
                matchingJsonPath("$.sourceBookId", equalTo(seeded.sourceBookingContext.establishmentId.toString())),
            )
            .withRequestBody(
                matchingJsonPath("$.targetBookId", equalTo(seeded.targetBookingContext.establishmentId.toString())),
            )
        LedgerWireMockResource.server.verify(
            5,
            request.withHeader(
                SyntheticTaint.KAFKA_HEADER,
                if (synthetic) equalTo(SyntheticTaint.TRUE_VALUE) else absent(),
            ),
        )
    }

    private fun newWorkflow(transactionId: UUID): com.openbank.transaction.application.workflow.BranchPaymentWorkflow =
        env.workflowClient.newWorkflowStub(
            com.openbank.transaction.application.workflow.BranchPaymentWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TASK_QUEUE)
                .setWorkflowId("payment-$transactionId")
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                .build(),
        )

    private fun seedPendingTransfer(synthetic: Boolean): Transaction {
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
        return onVertxContext {
            transactionRepository.save(
                transaction = transaction,
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
