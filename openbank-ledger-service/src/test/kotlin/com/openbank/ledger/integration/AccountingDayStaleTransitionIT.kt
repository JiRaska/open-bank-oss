// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.integration

import com.openbank.ledger.application.port.out.AccountingDayRepository
import com.openbank.ledger.domain.model.AccountingDayRecord
import com.openbank.ledger.domain.model.AccountingDayStatus
import com.openbank.ledger.domain.model.LedgerConflictException
import com.openbank.ledger.it.PostgresTestResource
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/** The losing update uses a consumed version, so the conflict never depends on thread timing. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class AccountingDayStaleTransitionIT {
    @Inject
    lateinit var days: AccountingDayRepository

    @Inject
    lateinit var dataSource: DataSource

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun message(day: AccountingDayRecord) =
        OutboxMessage(aggregateId = day.id, eventType = "AccountingDayTransitioned", payload = "{}")

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `consumed version returns conflict without a second transition or outbox event`() {
        val date = LocalDate.of(2019, 8, 12)
        val opened = AccountingDayRecord.open(date, Instant.now(), "operator", UUID.randomUUID())
        onEventLoop { days.saveOpened(opened, message(opened)) }

        val cutoff = opened.transitionTo(AccountingDayStatus.CUTOFF, "operator", Instant.now())
        onEventLoop { days.saveTransition(cutoff, opened.version, message(cutoff)) }

        assertThatThrownBy {
            onEventLoop { days.saveTransition(cutoff, opened.version, message(cutoff)) }
        }.isInstanceOf(LedgerConflictException::class.java)
            .hasMessageContaining("expected version ${opened.version}")

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select status, version from ledger_accounting_day where id = ?",
            ).use { statement ->
                statement.setObject(1, opened.id)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString("status")).isEqualTo("CUTOFF")
                    assertThat(rows.getLong("version")).isEqualTo(1L)
                }
            }
            connection.prepareStatement(
                "select count(*) from ledger_outbox where aggregate_id = ? and event_type = ?",
            ).use { statement ->
                statement.setObject(1, opened.id)
                statement.setString(2, "AccountingDayTransitioned")
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getLong(1)).isEqualTo(2L)
                }
            }
        }

        // The same dedicated conflict type is wired to the served HTTP operation's 409 mapper.
        given().contentType("application/json").`when`()
            .post("/api/v1/ledger/accounting-days/$date/transitions/CUTOFF")
            .then().statusCode(409)
    }
}
