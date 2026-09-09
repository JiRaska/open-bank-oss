// SPDX-License-Identifier: Apache-2.0
package com.openbank.lending.integration

import com.openbank.lending.application.port.out.LoanEventEmitter
import com.openbank.lending.application.port.out.LoanRepository
import com.openbank.lending.application.port.out.ProvisioningRepository
import com.openbank.lending.application.usecase.queueAllowance
import com.openbank.lending.domain.model.LoanProvisioningRecord
import com.openbank.lending.domain.model.LoanStatus
import com.openbank.lending.it.PostgresRedisTestResource
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.DelinquencyBucket
import com.openbank.libs.lending.Ifrs9Stage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.mutiny.Uni
import jakarta.annotation.security.PermitAll
import jakarta.inject.Inject
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/** Test-only HTTP adapter supplies the real Vert.x context; no repository or emitter mocks. */
@Path("/test/allowance-atomicity")
@PermitAll
class AllowanceAtomicityTestResource(
    private val loans: LoanRepository,
    private val provisioning: ProvisioningRepository,
    private val events: LoanEventEmitter,
) {
    @POST
    @Path("/{id}/{rollback}")
    fun exercise(@PathParam("id") id: UUID, @PathParam("rollback") rollback: Boolean): Uni<Response> =
        loans.withLocked(LoanId(id)) { found ->
            val loan = requireNotNull(found)
            val today = LocalDate.now()
            val amount = Money.of("900.00", "EUR")
            val record = LoanProvisioningRecord(
                loanId = loan.id, period = today.toString(), asOf = today,
                outstandingBalance = loan.principal, daysPastDue = 0, bucket = DelinquencyBucket.CURRENT,
                stage = Ifrs9Stage.STAGE_1, expectedCreditLoss = amount,
                createdAt = OffsetDateTime.now(), modelVersion = "atomicity-test-v1",
            )
            loans.update(loan.copy(status = LoanStatus.DELINQUENT))
                .flatMap { provisioning.save(record) }
                .flatMap { events.queueAllowance(loan, "loan:$id:atomicity", amount, today) }
                .flatMap { Panache.getSession().flatMap { it.flush() } }
                .flatMap {
                    if (rollback) {
                        Uni.createFrom().failure<Unit>(IllegalStateException("deliberate rollback after flush"))
                    } else {
                        Uni.createFrom().item(Unit)
                    }
                }
        }.map { Response.noContent().build() }
            .onFailure(IllegalStateException::class.java)
            .recoverWithItem { _: Throwable -> Response.status(409).build() }
}

@QuarkusTest
@QuarkusTestResource(LendingOutboxWriteIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class AllowanceOutboxAtomicityIT {
    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = "allowance-atomicity-officer", roles = ["ROLE_LENDING_OFFICER"])
    fun `loan snapshot and frozen allowance command commit together or all roll back`() {
        val applicationId = Given {
            contentType("application/json")
            body(
                """{"partyId":"${UUID.randomUUID()}","requestedAmount":{"amount":"10000.00","currency":{"code":"EUR"}},
                "nominalAnnualRate":0.05,"termPeriods":12,"firstDueDate":"${LocalDate.now().plusMonths(1)}"}""",
            )
        } When {
            post("/api/v1/lending/applications")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getString("id")
        }
        val loanId = UUID.randomUUID()
        seedLoan(loanId, UUID.fromString(applicationId))
        try {
            Given { contentType("application/json") } When { post("/test/allowance-atomicity/$loanId/true") } Then
                { statusCode(409) }
            assertRows(loanId, "ACTIVE", 0)
            Given { contentType("application/json") } When { post("/test/allowance-atomicity/$loanId/false") } Then
                { statusCode(204) }
            assertRows(loanId, "DELINQUENT", 1)
        } finally {
            cleanup(loanId, UUID.fromString(applicationId))
        }
    }

    private fun seedLoan(loanId: UUID, applicationId: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO loan(id, application_id, party_id, principal, currency, nominal_annual_rate,
                    term_periods, method, first_due_date, status)
                VALUES (?, ?, ?, 10000, 'EUR', 0.05, 12, 'ANNUITY', current_date + 30, 'ACTIVE')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, loanId)
                statement.setObject(2, applicationId)
                statement.setObject(3, UUID.randomUUID())
                statement.executeUpdate()
            }
        }
    }

    private fun assertRows(loanId: UUID, status: String, expected: Int) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT l.status,
                    (SELECT count(*) FROM loan_provisioning p WHERE p.loan_id = l.id),
                    (SELECT count(*) FROM lending_outbox o WHERE o.aggregate_id = l.id
                        AND o.event_type = 'lending.allowance.posting' AND o.status = 'PENDING')
                FROM loan l WHERE l.id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, loanId)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString(1)).isEqualTo(status)
                    assertThat(rows.getInt(2)).isEqualTo(expected)
                    assertThat(rows.getInt(3)).isEqualTo(expected)
                }
            }
        }
    }

    private fun cleanup(loanId: UUID, applicationId: UUID) {
        dataSource.connection.use { connection ->
            listOf(
                "DELETE FROM lending_outbox WHERE aggregate_id = ?" to loanId,
                "DELETE FROM loan_provisioning WHERE loan_id = ?" to loanId,
                "DELETE FROM loan WHERE id = ?" to loanId,
                "DELETE FROM loan_application WHERE id = ?" to applicationId,
            ).forEach { (sql, id) ->
                connection.prepareStatement(sql).use { statement ->
                    statement.setObject(1, id)
                    statement.executeUpdate()
                }
            }
        }
    }
}
