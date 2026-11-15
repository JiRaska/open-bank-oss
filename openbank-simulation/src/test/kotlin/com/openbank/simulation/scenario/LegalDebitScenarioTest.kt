// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.account.domain.model.Account
import com.openbank.account.domain.model.AccountStatus
import com.openbank.account.domain.model.AccountType
import com.openbank.account.domain.model.LegalAccountProtection
import com.openbank.account.domain.model.LegalProtectionRegime
import com.openbank.balance.domain.model.Balance
import com.openbank.balance.domain.model.LegalAttachment
import com.openbank.balance.domain.model.LegalDebitFunds
import com.openbank.balance.domain.model.ProtectedCreditCohort
import com.openbank.balance.domain.model.ProtectedCreditCohorts
import com.openbank.libs.domain.account.Iban
import com.openbank.libs.domain.identifiers.AccountId
import com.openbank.libs.domain.identifiers.CaseId
import com.openbank.libs.domain.identifiers.PartyId
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money
import com.openbank.libs.legal.LegalEffectDisposition
import com.openbank.libs.legal.LegalEffectIdentity
import com.openbank.libs.legal.LegalEffectRevision
import com.openbank.libs.legal.LegalEffectRevisionGuard
import com.openbank.libs.legal.LegalEffectRevisionOutcome
import com.openbank.party.domain.model.LegalDebitAuthority
import com.openbank.party.domain.model.LegalDebitConstraint
import com.openbank.party.domain.model.LegalDebitMandate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.math.BigDecimal
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.time.YearMonth
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Executes the real domain policies with synthetic, already verified court and protection facts.
 * The fixture amounts are arbitrary. The PostgreSQL test exercises fixture locking, not a payment rail.
 */
class LegalDebitScenarioTest {
    private val subject = PartyId(id(1))
    private val guardian = PartyId(id(2))
    private val debitId = AccountId(id(3))
    private val at = OffsetDateTime.parse("2026-11-15T12:00:00Z")

    @Test
    fun `CZ linked account preserves funded income and still requires an authorized actor`() {
        val scenario = scenario(LegalProtectionRegime.CZ_LINKED_ACCOUNT, "CZK", AccountId(id(4)))
        assertFalse(scenario.accepts(subject, "20"))
        assertTrue(scenario.accepts(guardian, "60"))
        assertFalse(scenario.accepts(guardian, "61"))
        val zero = Money.of("0", "CZK")
        assertEquals(zero, LegalDebitFunds.available(scenario.sourceBalance, subject, scenario.orders, zero))
        assertFalse(scenario.protection.permits(scenario.debit, scenario.source.copy(partyId = guardian.value)))
        assertFalse(scenario.protection.permits(scenario.source, scenario.source))
    }

    @Test
    fun `SK exemption coexists with two orders and release only lifts the named order`() {
        val scenario = scenario(LegalProtectionRegime.SK_EXEMPTION, "EUR")
        assertFalse(scenario.accepts(subject, "1"))
        assertTrue(scenario.accepts(guardian, "60"))
        assertFalse(scenario.accepts(guardian, "61"))
        val first = scenario.orders.first()
        val release = first.effect.copy(revision = 2, disposition = LegalEffectDisposition.RELEASED)
        val released = first.copy(effect = release)
        assertEquals(LegalEffectRevisionOutcome.STALE, LegalEffectRevisionGuard.evaluate(released.effect, first.effect))
        val remainingOrder = scenario.copy(orders = listOf(released, scenario.orders.last()))
        assertTrue(remainingOrder.accepts(guardian, "80"))
        assertFalse(remainingOrder.accepts(guardian, "81"))
        assertFalse(remainingOrder.accepts(subject, "1"))
    }

    @Test
    fun `DE appointment alone does not restrict self and P-Konto does not extend available credit`() {
        val scenario = scenario(LegalProtectionRegime.DE_P_KONTO, "EUR")
        val appointmentOnly = scenario.copy(authority = LegalDebitAuthority(subject, emptyList(), listOf(mandate())))
        assertTrue(appointmentOnly.accepts(subject, "60"))
        assertFalse(scenario.accepts(subject, "1"))
        assertTrue(scenario.accepts(guardian, "60"))
        assertFalse(scenario.accepts(guardian, "61"))
        assertFalse(scenario.accepts(guardian, "1000"))
        val jointMandate = mandate().copy(mayActAlone = false)
        val noIndependentRepresentative = scenario.copy(
            authority = LegalDebitAuthority(subject, listOf(constraint()), listOf(jointMandate)),
        )
        assertFalse(noIndependentRepresentative.accepts(guardian, "1"))
    }

    @Test
    fun `DE protected credit expires on Berlin month boundary and is consumed oldest first`() {
        val scenario = scenario(LegalProtectionRegime.DE_P_KONTO, "EUR")
        val older = ProtectedCreditCohort(
            1,
            Instant.parse("2026-07-15T12:00:00Z"),
            YearMonth.of(2026, 7),
            Money.of("40", "EUR"),
        )
        val newer = ProtectedCreditCohort(
            2,
            Instant.parse("2026-10-27T12:00:00Z"),
            YearMonth.of(2026, 10),
            Money.of("20", "EUR"),
        )
        val before = Instant.parse("2026-10-31T22:59:59Z")
        val boundary = Instant.parse("2026-10-31T23:00:00Z")
        val cohorts = listOf(newer, older)
        val onBoundary = scenario.copy(verifiedProtection = ProtectedCreditCohorts.available(cohorts, boundary))
        assertTrue(onBoundary.accepts(guardian, "20"))
        assertFalse(onBoundary.accepts(guardian, "21"))
        assertFalse(onBoundary.accepts(subject, "1"))
        val initial = ProtectedCreditCohorts.available(cohorts, before)
        assertTrue(scenario.copy(verifiedProtection = initial).accepts(guardian, "50"))
        val remaining = ProtectedCreditCohorts.consume(cohorts, before, Money.of("50", "EUR"))
        assertEquals(listOf(Money.of("10", "EUR"), Money.of("0", "EUR")), remaining.map { it.remaining })
    }

    @Test
    fun `account standing and pending credits still constrain a verified representative`() {
        val scenario = scenario(LegalProtectionRegime.DE_P_KONTO, "EUR")
        val frozen = scenario.debit.copy(status = AccountStatus.FROZEN)
        assertFalse(scenario.copy(debit = frozen).accepts(guardian, "1"))
        val pending = scenario.balance.copy(notYetEffectiveCredit = BigDecimal("95"))
        assertFalse(scenario.copy(balance = pending).accepts(guardian, "6"))
        assertTrue(scenario.copy(balance = pending).accepts(guardian, "5"))
    }

    @Test
    fun `postgres serializes competing reservations and observes revoked mandate in CZ SK and DE`() {
        PostgreSQLContainer("postgres:18.6-alpine").use { postgres ->
            postgres.start()
            val database = PGSimpleDataSource().apply {
                setURL(postgres.jdbcUrl)
                user = postgres.username
                password = postgres.password
            }
            database.connection.use { connection ->
                connection.createStatement().use {
                    it.execute(
                        """
                        CREATE TABLE legal_debit_fixture (
                            regime TEXT PRIMARY KEY,
                            reserved NUMERIC NOT NULL DEFAULT 0,
                            revoked BOOLEAN NOT NULL DEFAULT FALSE
                        )
                        """.trimIndent(),
                    )
                }
            }
            listOf(
                scenario(LegalProtectionRegime.CZ_LINKED_ACCOUNT, "CZK", AccountId(id(4))),
                scenario(LegalProtectionRegime.SK_EXEMPTION, "EUR"),
                scenario(LegalProtectionRegime.DE_P_KONTO, "EUR"),
            ).forEach { fixture -> verifyDatabaseRace(database, fixture) }
        }
    }

    private fun verifyDatabaseRace(database: PGSimpleDataSource, fixture: Scenario) {
        val regime = fixture.protection.regime.name
        updateFixture(database, regime, "INSERT INTO legal_debit_fixture (regime) VALUES (?)")
        assertFalse(tryReserve(database, fixture, subject, "1"), regime)
        val start = CyclicBarrier(2)
        val results = Executors.newFixedThreadPool(2).use { pool ->
            List(2) {
                pool.submit<Boolean> {
                    start.await(10, TimeUnit.SECONDS)
                    tryReserve(database, fixture, guardian, "40")
                }
            }.map { it.get(15, TimeUnit.SECONDS) }
        }
        assertEquals(1, results.count { it }, regime)
        val revoke = "UPDATE legal_debit_fixture SET revoked = TRUE WHERE regime = ? AND reserved = 40"
        updateFixture(database, regime, revoke)
        assertFalse(tryReserve(database, fixture, guardian, "1"), regime)
    }

    private fun updateFixture(database: PGSimpleDataSource, regime: String, sql: String) {
        database.connection.use { connection ->
            connection.prepareStatement(sql).use {
                it.setString(1, regime)
                assertEquals(1, it.executeUpdate())
            }
        }
    }

    /** Fixture transaction using the domain checks, synthetic projections and a PostgreSQL row lock. */
    private fun tryReserve(database: PGSimpleDataSource, fixture: Scenario, actor: PartyId, amount: String): Boolean =
        database.connection.use { connection ->
            connection.autoCommit = false
            val regime = fixture.protection.regime.name
            val allowed = lockedSnapshot(connection, fixture).accepts(actor, amount)
            if (allowed) {
                val sql = "UPDATE legal_debit_fixture SET reserved = reserved + ? WHERE regime = ?"
                connection.prepareStatement(sql).use {
                    it.setBigDecimal(1, amount.toBigDecimal())
                    it.setString(2, regime)
                    assertEquals(1, it.executeUpdate())
                }
            }
            connection.commit()
            allowed
        }

    private fun lockedSnapshot(connection: Connection, fixture: Scenario): Scenario = connection.prepareStatement(
        "SELECT reserved, revoked FROM legal_debit_fixture WHERE regime = ? FOR UPDATE",
    ).use {
        it.setString(1, fixture.protection.regime.name)
        it.executeQuery().use { row ->
            check(row.next())
            val reserved = row.getBigDecimal("reserved")
            val mandates = if (row.getBoolean("revoked")) emptyList() else listOf(mandate())
            fixture.copy(
                balance = fixture.balance.copy(
                    availableAmount = fixture.balance.availableAmount - reserved,
                    reservedAmount = reserved,
                ),
                authority = LegalDebitAuthority(subject, listOf(constraint()), mandates),
                verifiedProtection = Money.of(
                    fixture.verifiedProtection.amount - reserved,
                    fixture.debit.currency.code,
                ),
            )
        }
    }

    private fun scenario(regime: LegalProtectionRegime, currency: String, sourceId: AccountId = debitId): Scenario {
        val debit = account(debitId, currency)
        val source = if (sourceId == debitId) debit else account(sourceId, currency)
        return Scenario(
            debit = debit,
            source = source,
            balance = balance(debit, if (sourceId == debitId) "100" else "60"),
            sourceBalance = balance(source, "100"),
            protection = LegalAccountProtection(effect(8), regime, debitId, sourceId),
            orders = listOf(
                LegalAttachment(effect(9), sourceId, Money.of("100", currency)),
                LegalAttachment(effect(10), sourceId, Money.of("20", currency)),
            ),
            authority = LegalDebitAuthority(subject, listOf(constraint()), listOf(mandate())),
            verifiedProtection = Money.of("60", currency),
        )
    }

    private fun account(accountId: AccountId, currency: String): Account = Account(
        id = accountId.value,
        accountNumber = Iban.of("CZ6508000000192000145399"),
        accountType = AccountType.CURRENT,
        partyId = subject.value,
        productId = id(11),
        currency = CurrencyCode.of(currency),
        status = AccountStatus.ACTIVE,
        openedAt = at.toInstant(),
        closedAt = null,
        version = 0,
    )

    private fun balance(account: Account, amount: String): Balance = Balance(
        id = id(5),
        accountId = account.id,
        currency = account.currency.code,
        bookedAmount = BigDecimal(amount),
        availableAmount = BigDecimal(amount),
        reservedAmount = BigDecimal.ZERO,
        pendingAmount = BigDecimal.ZERO,
        updatedAt = at,
        version = 0,
        arrangedOverdraftLimit = BigDecimal("500"),
    )

    private fun id(number: Long) = UUID(0, number)

    private fun effect(number: Long) = LegalEffectRevision(
        LegalEffectIdentity(id(number), CaseId(id(20 + number)), subject),
        1,
        LegalEffectDisposition.ACTIVE,
        "a".repeat(64),
    )

    private fun constraint() = LegalDebitConstraint(effect(6), debitId)

    private fun mandate() = LegalDebitMandate(effect(7), debitId, guardian, mayActAlone = true)

    private data class Scenario(
        val debit: Account,
        val source: Account,
        val balance: Balance,
        val sourceBalance: Balance,
        val protection: LegalAccountProtection,
        val orders: List<LegalAttachment>,
        val authority: LegalDebitAuthority,
        val verifiedProtection: Money,
    ) {
        fun accepts(actor: PartyId, amount: String): Boolean {
            val owner = PartyId(debit.partyId)
            val requested = Money.of(amount, debit.currency.code)
            if (!authority.appliesTo(owner) || !authority.permits(AccountId(debit.id), actor)) return false
            if (!protection.permits(debit, source) || !debit.canDebit(requested)) return false
            val applicable = orders.filter { it.account.value == debit.id }
            return LegalDebitFunds.reserve(balance, owner, applicable, verifiedProtection, requested) != null
        }
    }
}
