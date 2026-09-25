// SPDX-License-Identifier: Apache-2.0
package com.openbank.lending.integration

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
                    assertThat(payload).contains(pending.guaranteeId.toString(), "\"schemaVersion\":1")
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
