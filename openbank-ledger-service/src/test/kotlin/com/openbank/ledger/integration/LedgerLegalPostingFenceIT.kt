// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.integration

import com.openbank.ledger.application.port.`in`.JournalLineRequest
import com.openbank.ledger.application.port.`in`.LedgerUseCase
import com.openbank.ledger.application.port.`in`.PostJournalCommand
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.ledger.domain.model.LedgerConflictException
import com.openbank.ledger.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Uses the actual Ledger service and PostgreSQL. Permit publication is an explicit test fixture. */
@QuarkusTest
@QuarkusTestResource(value = PostgresRedpandaTestResource::class, restrictToAnnotatedClass = true)
class LedgerLegalPostingFenceIT {
    @Inject
    lateinit var ledger: LedgerUseCase

    private val pocket = UUID.randomUUID()
    private val settlement = UUID.randomUUID()

    @ParameterizedTest
    @ValueSource(strings = ["CZK", "EUR"])
    fun `journal rejects missing and changed authorization and consumes matching intent once`(currency: String) {
        val command = command(currency)
        enroll(currency)
        assertDenied(command)
        publish(command, 1)
        val differentCredit = command.lines.last().copy(subAccountId = UUID.randomUUID())
        val tampered = command.copy(lines = listOf(command.lines.first(), differentCredit))
        assertDenied(tampered)

        val posted = post(command)
        assertThat(count("journal_entries where transaction_id = ?", command.transactionId)).isEqualTo(1)
        assertThat(post(command)).isEqualTo(posted)
        advanceRevision()
        publish(command, 2)
        assertDenied(command.copy(idempotencyKey = UUID.randomUUID().toString()))
    }

    @Test
    fun `revocation racing journal insertion wins when the revocation holds the lock first`() {
        val command = command()
        enroll("CZK")
        publish(command, 1)
        connection().use { revocation ->
            revocation.autoCommit = false
            val blocker = backendPid(revocation)
            advanceRevision(revocation)
            Executors.newSingleThreadExecutor().use { executor ->
                val result = executor.submit<Result<UUID>> { runCatching { post(command) } }
                try {
                    awaitAdvisoryWait(blocker)
                    revocation.commit()
                    assertThat(result.get(10, TimeUnit.SECONDS).exceptionOrNull())
                        .isInstanceOf(LedgerConflictException::class.java)
                } finally {
                    revocation.rollback()
                }
            }
        }
        assertThat(count("journal_entries where transaction_id = ?", command.transactionId)).isZero()
        val consumed = "ledger_legal_posting_permit where transaction_id = ? AND consumed_by IS NOT NULL"
        assertThat(count(consumed, command.transactionId)).isZero()
        publish(command, 2)
        post(command)
    }

    private fun awaitAdvisoryWait(blocker: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        connection().use { observer ->
            observer.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE locktype = 'advisory' " +
                    "AND NOT granted AND ? = ANY(pg_blocking_pids(pid)))",
            ).use { sql ->
                sql.setInt(1, blocker)
                while (System.nanoTime() < deadline && !sql.queryFlag()) {
                    Thread.sleep(20)
                }
                check(sql.queryFlag()) { "Journal writer did not wait for the revocation lock" }
            }
        }
    }

    private fun PreparedStatement.queryFlag(): Boolean = executeQuery().use { it.next() && it.getBoolean(1) }

    private fun backendPid(db: Connection): Int = db.createStatement().use { sql ->
        sql.executeQuery("SELECT pg_backend_pid()").use {
            check(it.next())
            it.getInt(1)
        }
    }

    private fun count(source: String, key: UUID): Long = connection().use { db ->
        db.prepareStatement("SELECT COUNT(*) FROM $source").use { sql ->
            sql.setObject(1, key)
            sql.executeQuery().use {
                check(it.next())
                it.getLong(1)
            }
        }
    }

    private fun connection(): Connection {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }

    private fun assertDenied(command: PostJournalCommand) {
        assertThatThrownBy { post(command) }.isInstanceOf(LedgerConflictException::class.java)
            .hasMessageContaining("Current legal posting authorization")
    }

    private fun post(command: PostJournalCommand): UUID = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { ledger.postJournal(command).id }.asUni()
    }

    private fun enroll(currency: String) {
        connection().use { db ->
            db.prepareStatement("INSERT INTO ledger_legal_posting_scope VALUES (?, ?, 1)").use {
                it.setObject(1, pocket)
                it.setString(2, currency)
                it.executeUpdate()
            }
        }
    }

    private fun advanceRevision() {
        connection().use(::advanceRevision)
    }

    private fun advanceRevision(db: Connection) {
        db.prepareStatement("UPDATE ledger_legal_posting_scope SET revision = revision + 1 WHERE account_id = ?").use {
            it.setObject(1, pocket)
            assertThat(it.executeUpdate()).isEqualTo(1)
        }
    }

    private fun publish(command: PostJournalCommand, revision: Long) {
        val intent = JsonObject()
            .put("version", 1)
            .put("synthetic", false)
            .put("entry_date", command.entryDate.toString())
            .put("value_date", command.valueDate.toString())
            .putNull("reversal_of")
            .put("lines", JsonArray(command.lines.map(::lineIntent)))
        connection().use { db ->
            db.prepareStatement(
                """
                INSERT INTO ledger_legal_posting_permit
                  (account_id, currency, transaction_id, revision, intent, valid_until, verification_ref)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), now() + interval '5 minutes', gen_random_uuid())
                """.trimIndent(),
            ).use {
                it.setObject(1, pocket)
                it.setString(2, command.lines.first().baseCurrencyCode)
                it.setObject(3, command.transactionId)
                it.setLong(4, revision)
                it.setString(5, intent.encode())
                it.executeUpdate()
            }
        }
    }

    private fun lineIntent(line: JournalLineRequest): JsonObject = JsonObject()
        .put("gl_account_id", line.glAccountId.toString())
        .put("side", line.side.name)
        .put("amount", line.amount)
        .put("currency", line.currencyCode)
        .put("base_amount", line.baseAmount)
        .put("base_currency", line.baseCurrencyCode)
        .putNull("fx_rate")
        .put("sub_account_id", line.subAccountId?.toString())

    private fun command(currency: String = "CZK"): PostJournalCommand {
        val today = LocalDate.now()
        val amount = BigDecimal("20.00")
        val gl = if (currency == "CZK") {
            "a0000000-0000-0000-0000-000000000002"
        } else {
            "a0000000-0000-0000-0000-000000002101"
        }
        val debit = JournalLineRequest(
            glAccountId = UUID.fromString(gl),
            side = JournalSide.DEBIT,
            amount = amount,
            currencyCode = currency,
            fxRate = null,
            baseAmount = amount,
            baseCurrencyCode = currency,
            subAccountId = pocket,
        )
        return PostJournalCommand(
            idempotencyKey = UUID.randomUUID().toString(),
            transactionId = UUID.randomUUID(),
            entryDate = today,
            valueDate = today,
            description = "Legal authorization fixture",
            lines = listOf(debit, debit.copy(side = JournalSide.CREDIT, subAccountId = settlement)),
            postedBy = UUID.randomUUID(),
        )
    }
}
