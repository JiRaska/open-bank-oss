// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0.

package com.openbank.balance.integration

import com.openbank.balance.domain.reconciliation.BranchLedgerLine
import com.openbank.balance.domain.reconciliation.BranchLedgerSnapshot
import com.openbank.balance.infrastructure.client.LedgerBranchBooksClient
import com.openbank.balance.it.PostgresRedpandaTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.smallrye.mutiny.Uni
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

@QuarkusTest
@TestProfile(BranchBookReconciliationIT.Profile::class)
@TestSecurity(user = "synthetic-branch-accountant", roles = ["ROLE_OPERATOR", "ROLE_API"])
class BranchBookReconciliationIT {
    class Profile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true
        override fun testResources() =
            listOf(QuarkusTestProfile.TestResourceEntry(PostgresRedpandaTestResource::class.java))
        override fun getConfigOverrides() = mapOf(
            "openbank.balance.projection.enabled" to "true",
            "quarkus.scheduler.enabled" to "true",
            "openbank.balance.branch-reconciliation.interval" to "PT1S",
            "openbank.outbox.dispatch-enabled" to "false",
        )
    }

    @Inject lateinit var dataSource: DataSource

    @Test
    fun `HTTP branch tie-out detects swapped books and the real scheduler resumes after a ledger outage`() {
        val bank = UUID.randomUUID()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val date = LocalDate.now(ZoneOffset.UTC)
        val unavailable = AtomicBoolean(false)
        val swapped = AtomicBoolean(false)
        val calls = AtomicInteger()
        val client = object : LedgerBranchBooksClient {
            override fun snapshot(bankId: UUID, asOf: String): Uni<BranchLedgerSnapshot> {
                calls.incrementAndGet()
                if (unavailable.get()) return Uni.createFrom().failure(java.io.IOException("synthetic ledger outage"))
                val lines = if (swapped.get()) {
                    listOf(line(first, "30"), line(second, "20"))
                } else {
                    listOf(line(first, "20"), line(second, "30"))
                }
                return Uni.createFrom().item(BranchLedgerSnapshot(bankId, LocalDate.parse(asOf), lines))
            }
        }
        QuarkusMock.installMockForType(client, LedgerBranchBooksClient::class.java, RestClient.LITERAL)
        seed(bank, first, "25", date.plusDays(1), "5")
        seed(bank, second, "30", null, null)
        val matched = given().queryParam("bankId", bank).queryParam("asOf", date.toString())
            .post(PATH).then().statusCode(201).extract().jsonPath()
        assertThat(matched.getBoolean("complete")).isTrue()
        swapped.set(true)
        val drift = given().queryParam("bankId", bank).queryParam("asOf", date.toString())
            .post(PATH).then().statusCode(201).extract().jsonPath()
        assertThat(drift.getBoolean("bankMatches")).isTrue()
        assertThat(drift.getBoolean("booksMatch")).isFalse()
        assertThat(drift.getBoolean("complete")).isFalse()
        unavailable.set(true)
        val before = calls.get()
        await { calls.get() > before + 1 }
        val captured = latest(bank).getString("generatedAt")
        Thread.sleep(250)
        assertThat(latest(bank).getString("generatedAt")).isEqualTo(captured)
        swapped.set(false)
        unavailable.set(false)
        // No POST retry: the real scheduler must publish fresh successful evidence.
        await {
            val report = latest(bank)
            report.getString("generatedAt") > captured && report.getBoolean("complete")
        }
    }

    private fun latest(bank: UUID) = given().queryParam("bankId", bank).get("$PATH/latest")
        .then().statusCode(200).extract().jsonPath()

    private fun line(book: UUID, amount: String) = BranchLedgerLine(
        book,
        "EUR",
        BigDecimal(amount),
        BigDecimal(amount),
        BigDecimal(amount),
    )

    private fun seed(bank: UUID, book: UUID, booked: String, future: LocalDate?, delta: String?) {
        val account = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO account_booking_context(account_id,bank_id,establishment_id,host_country,mode) " +
                    "VALUES (?,?,?,'AT','BRANCH')",
            ).use {
                it.setObject(1, account)
                it.setObject(2, bank)
                it.setObject(3, book)
                it.executeUpdate()
            }
            c.prepareStatement(
                "INSERT INTO balances(account_id,currency,booked_amount,available_amount) VALUES (?,'EUR',?,?)",
            ).use {
                it.setObject(1, account)
                it.setBigDecimal(2, BigDecimal(booked))
                it.setBigDecimal(3, BigDecimal(booked))
                it.executeUpdate()
            }
            if (future !=
                null
            ) {
                c.prepareStatement(
                    "INSERT INTO ledger_projection_event(journal_entry_id,account_id,currency,delta," +
                        "transaction_id,entry_date,applied_at,branch_book_id) VALUES (?,?,'EUR',?,?,?,now(),?)",
                ).use {
                    it.setObject(1, UUID.randomUUID())
                    it.setObject(2, account)
                    it.setBigDecimal(3, BigDecimal(delta))
                    it.setObject(4, UUID.randomUUID())
                    it.setObject(5, future)
                    it.setObject(6, book)
                    it.executeUpdate()
                }
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            if (condition())return
            Thread.sleep(100)
        }
        error("Branch-book reconciliation did not recover")
    }

    private companion object {
        const val PATH = "/api/v1/balances/reconciliation/branch-books"
    }
}
