// SPDX-License-Identifier: Apache-2.0
package com.openbank.lending.integration

import com.openbank.lending.application.port.out.GraphGuaranteeIdempotencyConflict
import com.openbank.lending.application.port.out.GraphGuaranteeRepository
import com.openbank.lending.domain.model.GraphGuaranteeProposal
import com.openbank.lending.domain.model.GraphGuaranteeStatus
import com.openbank.lending.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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

/** Real-Postgres transaction proof for Lending graph approval and its minimized event. */
@QuarkusTest
@QuarkusTestResource(LendingBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(value = PostgresRedisTestResource::class, restrictToAnnotatedClass = true)
class LendingGuaranteeOutboxAtomicityIT {
    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var guarantees: GraphGuaranteeRepository

    @Test
    fun `guarantee approval and minimized outbox reference commit together`() {
        val loanId = dataSource.connection.use(::createLoanForGuarantee)
        val contractId = UUID.randomUUID()
        val proposedAt = Instant.now().minusSeconds(60)
        val proposal = GraphGuaranteeProposal(
            contractId, 1, null, loanId, UUID.randomUUID(), BigDecimal("500.00"), "EUR",
            BigDecimal("0.5"), 1, proposedAt, null, UUID.randomUUID(), "a".repeat(64),
        )
        val pending = onVertx { guarantees.propose(proposal, "maker", proposedAt) }

        assertThatThrownBy {
            onVertx { guarantees.decide(pending.guaranteeId, GraphGuaranteeStatus.APPROVED, "checker", Instant.EPOCH) }
        }.hasMessageContaining("lending_outbox_created_at_plausible")
        assertThat(onVertx { guarantees.find(pending.guaranteeId) }?.status).isEqualTo(GraphGuaranteeStatus.PENDING)
        assertThat(outboxCount(contractId)).isZero()

        val approved = onVertx {
            guarantees.decide(pending.guaranteeId, GraphGuaranteeStatus.APPROVED, "checker", Instant.now())
        }
        assertThat(approved.status).isEqualTo(GraphGuaranteeStatus.APPROVED)
        assertThat(outboxCount(contractId)).isEqualTo(1)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT payload FROM lending_outbox WHERE aggregate_id = ? AND event_type = ?",
            ).use { statement ->
                statement.setObject(1, contractId)
                statement.setString(2, "lending.graph.guarantee.approved")
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    val payload = rows.getString(1)
                    assertThat(payload).contains(
                        pending.guaranteeId.toString(),
                        proposal.loanId.toString(),
                        "\"schemaVersion\":1",
                    )
                    assertThat(payload).doesNotContain(
                        proposal.guarantorPartyId.toString(),
                        proposal.sourceDocumentId.toString(),
                        proposal.sourceSha256,
                        "maker",
                        "checker",
                    )
                    assertThat(rows.next()).isFalse()
                }
            }
        }
    }

    @Test
    fun `retries retain the original response and approval creates only one event`() {
        val loanId = dataSource.connection.use(::createLoanForGuarantee)
        val proposal = proposalFor(loanId)
        val at = Instant.now()
        val proposalKey = "proposal-${UUID.randomUUID()}"
        val first = onVertx {
            guarantees.proposeIdempotent(proposal, "maker", at, proposalKey, "a".repeat(64))
        }
        assertThat(first.status).isEqualTo(GraphGuaranteeStatus.PENDING)
        val decisionKey = "decision-${UUID.randomUUID()}"
        val approved = onVertx {
            guarantees.decideIdempotent(
                loanId,
                first.guaranteeId,
                GraphGuaranteeStatus.APPROVED,
                "checker",
                at.plusSeconds(1),
                decisionKey,
                "b".repeat(64),
            )
        }
        assertThat(approved.status).isEqualTo(GraphGuaranteeStatus.APPROVED)
        val pendingReplay = onVertx {
            guarantees.proposeIdempotent(proposal, "maker", at.plusSeconds(2), proposalKey, "a".repeat(64))
        }
        assertThat(pendingReplay).isEqualTo(first)
        assertThatThrownBy {
            onVertx {
                guarantees.proposeIdempotent(proposal, "maker", at.plusSeconds(2), proposalKey, "e".repeat(64))
            }
        }.isInstanceOf(GraphGuaranteeIdempotencyConflict::class.java)
        val replay = onVertx {
            guarantees.decideIdempotent(
                loanId,
                first.guaranteeId,
                GraphGuaranteeStatus.APPROVED,
                "checker",
                at.plusSeconds(2),
                decisionKey,
                "b".repeat(64),
            )
        }
        assertThat(replay).isEqualTo(approved)
        assertThat(outboxCount(proposal.contractId)).isEqualTo(1)
        assertThatThrownBy {
            onVertx {
                guarantees.decideIdempotent(
                    loanId,
                    first.guaranteeId,
                    GraphGuaranteeStatus.REJECTED,
                    "checker",
                    at.plusSeconds(2),
                    decisionKey,
                    "c".repeat(64),
                )
            }
        }.isInstanceOf(GraphGuaranteeIdempotencyConflict::class.java)
    }

    @Test
    fun `concurrent same-key proposals create one fact and one receipt`() {
        val loanId = dataSource.connection.use(::createLoanForGuarantee)
        val proposal = proposalFor(loanId)
        val key = "concurrent-${UUID.randomUUID()}"
        val pool = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val calls = (1..2).map {
                CompletableFuture.supplyAsync({
                    ready.countDown()
                    start.await()
                    onVertx {
                        guarantees.proposeIdempotent(proposal, "maker", Instant.now(), key, "d".repeat(64))
                    }
                }, pool)
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            val results = calls.map { it.get(30, TimeUnit.SECONDS) }
            assertThat(results[0]).isEqualTo(results[1])
            assertThat(factCount(proposal.contractId)).isEqualTo(1)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `concurrent same-key approvals create one decision and one outbox reference`() {
        val loanId = dataSource.connection.use(::createLoanForGuarantee)
        val proposal = proposalFor(loanId)
        val pending = onVertx { guarantees.propose(proposal, "maker", Instant.now().minusSeconds(1)) }
        val key = "concurrent-decision-${UUID.randomUUID()}"
        val pool = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val calls = (1..2).map {
                CompletableFuture.supplyAsync({
                    ready.countDown()
                    start.await()
                    onVertx {
                        guarantees.decideIdempotent(
                            loanId,
                            pending.guaranteeId,
                            GraphGuaranteeStatus.APPROVED,
                            "checker",
                            Instant.now(),
                            key,
                            "f".repeat(64),
                        )
                    }
                }, pool)
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            val results = calls.map { it.get(30, TimeUnit.SECONDS) }
            assertThat(results[0]).isEqualTo(results[1])
            assertThat(results[0].status).isEqualTo(GraphGuaranteeStatus.APPROVED)
            assertThat(outboxCount(proposal.contractId)).isEqualTo(1)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun proposalFor(loanId: UUID) = GraphGuaranteeProposal(
        UUID.randomUUID(), 1, null, loanId, UUID.randomUUID(), BigDecimal("500.00"), "EUR",
        BigDecimal("0.5"), 1, Instant.now(), null, UUID.randomUUID(), "a".repeat(64),
    )

    private fun <T> onVertx(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        uni(CoroutineScope(Dispatchers.Unconfined)) { block() }
    }

    private fun outboxCount(contractId: UUID): Long = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT count(*) FROM lending_outbox WHERE aggregate_id = ? AND event_type = ?",
        ).use { statement ->
            statement.setObject(1, contractId)
            statement.setString(2, "lending.graph.guarantee.approved")
            statement.executeQuery().use { rows ->
                assertThat(rows.next()).isTrue()
                rows.getLong(1)
            }
        }
    }

    private fun factCount(contractId: UUID): Long = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT count(*) FROM lending_graph_guarantee WHERE contract_id = ?",
        ).use { statement ->
            statement.setObject(1, contractId)
            statement.executeQuery().use { rows ->
                assertThat(rows.next()).isTrue()
                rows.getLong(1)
            }
        }
    }

    private fun createLoanForGuarantee(connection: Connection): UUID {
        val applicationId = UUID.randomUUID()
        val loanId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        connection.prepareStatement(
            """INSERT INTO loan_application
               (id, party_id, requested_amount, currency, nominal_annual_rate,
                term_periods, first_due_date, proposed_by, status)
               VALUES (?, ?, 1000, 'EUR', 0.05, 12, current_date + 30, 'loan-maker', 'SUBMITTED')""",
        ).use { statement ->
            statement.setObject(1, applicationId)
            statement.setObject(2, partyId)
            assertThat(statement.executeUpdate()).isEqualTo(1)
        }
        connection.prepareStatement(
            """INSERT INTO loan
               (id, application_id, party_id, principal, currency, nominal_annual_rate,
                term_periods, method, first_due_date)
               VALUES (?, ?, ?, 1000, 'EUR', 0.05, 12, 'ANNUITY', current_date + 30)""",
        ).use { statement ->
            statement.setObject(1, loanId)
            statement.setObject(2, applicationId)
            statement.setObject(3, partyId)
            assertThat(statement.executeUpdate()).isEqualTo(1)
        }
        return loanId
    }
}
