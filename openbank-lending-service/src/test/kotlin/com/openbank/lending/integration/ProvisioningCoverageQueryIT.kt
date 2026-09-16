// SPDX-License-Identifier: Apache-2.0
package com.openbank.lending.integration

import com.openbank.lending.application.port.out.ProvisioningCoverageRepository
import com.openbank.lending.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class ProvisioningCoverageQueryIT {
    @Inject
    lateinit var loans: ProvisioningCoverageRepository

    @Inject
    lateinit var dataSource: DataSource

    private fun missing(period: String): Long =
        VertxContextSupport.subscribeAndAwait { loans.countUnprovisioned(period) }

    private fun sql(query: String, vararg parameters: Any) {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            connection.prepareStatement(query).use { statement ->
                parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
        }
    }

    private fun provision(loan: UUID, period: String) = sql(
        """INSERT INTO loan_provisioning
            (loan_id,period,as_of,outstanding_balance,currency,days_past_due,bucket,stage,expected_credit_loss,model_version)
            VALUES (?,?,'2099-06-01',100,'EUR',0,'CURRENT','STAGE_1',1,'coverage-test-v1')
        """.trimIndent(),
        loan,
        period,
    )

    @Test
    fun `coverage counts every eligible exposure missing the requested period`() {
        val period = "2099-06"
        val baseline = missing(period)
        val application = UUID.randomUUID()
        val active = UUID.randomUUID()
        val closed = UUID.randomUUID()
        val defaulted = UUID.randomUUID()
        val delinquent = UUID.randomUUID()
        try {
            sql(
                """INSERT INTO loan_application
                    (id,party_id,requested_amount,currency,nominal_annual_rate,term_periods,first_due_date,status,proposed_by)
                    VALUES (?,?,100,'EUR',0.05,12,'2099-07-01','DISBURSED','coverage-test')
                """.trimIndent(),
                application,
                UUID.randomUUID(),
            )
            for ((id, status) in listOf(
                active to "ACTIVE",
                closed to "CLOSED",
                defaulted to "DEFAULTED",
                delinquent to "DELINQUENT",
            )) {
                sql(
                    """INSERT INTO loan
                        (id,application_id,party_id,principal,currency,nominal_annual_rate,term_periods,method,first_due_date,status)
                        VALUES (?,?,?,100,'EUR',0.05,12,'ANNUITY','2099-07-01',?::loan_status)
                    """.trimIndent(),
                    id,
                    application,
                    UUID.randomUUID(),
                    status,
                )
            }
            provision(closed, period)
            provision(active, "2099-05")
            provision(defaulted, "2099-05")
            provision(delinquent, "2099-05")
            assertThat(missing(period)).isEqualTo(baseline + 3)

            provision(active, period)
            provision(defaulted, period)
            provision(delinquent, period)
            assertThat(missing(period)).isEqualTo(baseline)
        } finally {
            // Shared test database: remove only this test's rows, never truncate another IT's fixtures.
            sql("DELETE FROM loan_provisioning WHERE loan_id IN (?,?,?,?)", active, closed, defaulted, delinquent)
            sql("DELETE FROM loan WHERE id IN (?,?,?,?)", active, closed, defaulted, delinquent)
            sql("DELETE FROM loan_application WHERE id = ?", application)
        }
    }
}
