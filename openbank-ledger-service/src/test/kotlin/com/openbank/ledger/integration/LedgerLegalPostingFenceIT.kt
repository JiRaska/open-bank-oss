// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.integration

import com.openbank.ledger.application.port.`in`.JournalLineRequest
import com.openbank.ledger.application.port.`in`.LedgerUseCase
import com.openbank.ledger.application.port.`in`.PostJournalCommand
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.ledger.domain.model.LedgerConflictException
import com.openbank.ledger.infrastructure.persistence.repository.LegalPostingPermitIssuer
import com.openbank.ledger.infrastructure.persistence.repository.VerifiedDebitReservation
import com.openbank.ledger.it.PostgresRedpandaTestResource
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Actual issuer and Ledger service on PostgreSQL, with synthetic legal-review and Balance evidence. */
@QuarkusTest
@QuarkusTestResource(value = PostgresRedpandaTestResource::class, restrictToAnnotatedClass = true)
class LedgerLegalPostingFenceIT {
    @Inject
    lateinit var ledger: LedgerUseCase

    @Inject
    lateinit var issuePermit: LegalPostingPermitIssuer

    private val pocket = UUID.randomUUID()
    private val settlement = UUID.randomUUID()

    @ParameterizedTest
    @CsvSource("CZ,CZK", "SK,EUR", "DE,EUR")
    fun `verified reservation reaches Ledger exactly once`(country: String, currency: String) {
        val command = command(currency).copy(description = "Legal posting fixture $country")
        enroll(currency)
        assertDenied(command)
        val reservation = reservation(command, 1)
        assertThat(publish(command, reservation)).isTrue()
        assertThat(publish(command, reservation)).isTrue()
        assertThat(publish(command, reservation.copy(actorPartyId = UUID.randomUUID()))).isFalse()
        val differentCredit = command.lines.last().copy(subAccountId = UUID.randomUUID())
        val tampered = command.copy(lines = listOf(command.lines.first(), differentCredit))
        assertDenied(tampered)

        val posted = post(command)
        assertThat(count("journal_entries where transaction_id = ?", command.transactionId)).isEqualTo(1)
        assertThat(post(command)).isEqualTo(posted)
        assertThat(applyRevision(currency, 2)).isTrue()
        assertThat(applyRevision(currency, 1)).isFalse()
        assertThat(publish(command, reservation(command, 2))).isFalse()
        assertDenied(command.copy(idempotencyKey = UUID.randomUUID().toString()))
    }

    @Test
    fun `revocation racing journal insertion wins when the revocation holds the lock first`() {
        val command = command()
        enroll("CZK")
        assertThat(publish(command, reservation(command, 1))).isTrue()
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
        assertThat(publish(command, reservation(command, 1))).isFalse()
        assertThat(publish(command, reservation(command, 2))).isTrue()
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
        assertThat(applyRevision(currency, 1)).isTrue()
        assertThat(applyRevision(currency, 1)).isTrue()
    }

    private fun applyRevision(currency: String, revision: Long): Boolean = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async {
            issuePermit.applyRevision(pocket, CurrencyCode.of(currency), revision)
        }.asUni()
    }

    private fun advanceRevision(db: Connection) {
        db.prepareStatement("SELECT ledger_apply_legal_pocket_revision(?, 'CZK', 2)").use {
            it.setObject(1, pocket)
            assertThat(it.queryFlag()).isTrue()
        }
    }

    @Test
    fun `issuer refuses an expired or mismatched reservation and does not reuse it across instructions`() {
        val command = command()
        val facts = reservation(command, 1)
        assertThat(publish(command, facts)).isFalse()
        enroll("CZK")
        assertThat(publish(command, facts.copy(reservationExpiresAt = Instant.EPOCH))).isFalse()
        assertThat(publish(command, facts.copy(amount = Money.of("1.00", "CZK")))).isFalse()
        assertThat(publish(command, facts)).isTrue()
        val otherPayment = command.copy(
            transactionId = UUID.randomUUID(),
            idempotencyKey = UUID.randomUUID().toString(),
        )
        assertThat(publish(otherPayment, facts.copy(transactionId = otherPayment.transactionId))).isFalse()
        assertDenied(otherPayment)
    }

    private fun reservation(command: PostJournalCommand, revision: Long): VerifiedDebitReservation {
        val line = command.lines.first()
        val expiry = Instant.now().plusSeconds(TimeUnit.MINUTES.toSeconds(5))
        return VerifiedDebitReservation(
            accountId = pocket,
            transactionId = command.transactionId,
            amount = Money.of(line.baseAmount, line.baseCurrencyCode),
            revision = revision,
            verificationRef = UUID.randomUUID(),
            actorPartyId = UUID.randomUUID(),
            reservationRef = UUID.randomUUID(),
            verificationExpiresAt = expiry,
            reservationExpiresAt = expiry,
        )
    }

    private fun publish(command: PostJournalCommand, facts: VerifiedDebitReservation): Boolean =
        VertxContextSupport.subscribeAndAwait {
            CoroutineScope(Dispatchers.Unconfined).async { issuePermit.issue(command, facts) }.asUni()
        }

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
