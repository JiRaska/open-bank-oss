// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.migration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.account.CzechAccountNumber
import com.openbank.libs.domain.account.Iban
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.math.BigInteger
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * V35 re-issues every Czech account number that fails the ČNB mod-11 checksum (#16748).
 *
 * Runs the real migration chain against a real Postgres: V1..V34, seed rows on both sides of the
 * mod-11 boundary, then V35. The oracle for "valid" is the same [CzechAccountNumber] the generator
 * and domestic-payment use, never the migration's own SQL helpers — otherwise a wrong weight in
 * the migration would be checked against itself.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountNumberReissueMigrationIT {
    private val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"))
        .withUsername("openbank").withPassword("openbank").withDatabaseName("openbank_accounts_it")
    private val migrations = Path.of("src/main/resources/db/migration").toAbsolutePath()
    private val mapper = ObjectMapper()

    private val validBase = CzechAccountNumber.generateBase()
    private val validPrefixValidBase = UUID.randomUUID()
    private val invalidBaseByOne = UUID.randomUUID()
    private val invalidPrefixOnly = UUID.randomUUID()
    private val collisionVictim = UUID.randomUUID()
    private val collisionHolder = UUID.randomUUID()
    private val foreignIban = UUID.randomUUID()

    private lateinit var before: Map<UUID, String>

    @BeforeAll
    fun migrate() {
        postgres.start()
        flyway("34").migrate()
        connection().use { db ->
            // Valid: mod-11 base, empty prefix. Must be left exactly as it is.
            seed(db, validPrefixValidBase, iban("0000", "000000", validBase))
            // One past the boundary: the last base digit carries weight 1, so +1 breaks mod-11.
            seed(db, invalidBaseByOne, iban("0000", "000000", bumpLast(validBase)))
            // Base fine, prefix wrong ("000001": weighted sum 1). Only the prefix should change.
            val otherValid = distinctValidBase(validBase)
            seed(db, invalidPrefixOnly, iban("0000", "000001", otherValid))
            // The first candidate the migration derives for this invalid base is already taken.
            val takenBase = distinctValidBase(validBase, otherValid)
            seed(db, collisionHolder, iban("0000", "000000", takenBase))
            seed(db, collisionVictim, iban("0000", "000000", bumpLast(takenBase)))
            // Not a Czech IBAN: out of scope, untouched.
            seed(db, foreignIban, "DE89370400440532013000")
            before = numbers(db)
        }
        flyway("35").migrate()
    }

    @AfterAll
    fun stop() = postgres.stop()

    @Test
    fun `after V35 no Czech account number fails mod-11`() {
        connection().use { db ->
            val invalid = numbers(db).values
                .filter { it.matches(Regex("CZ\\d{22}")) }
                .filterNot { CzechAccountNumber.isValid(it.substring(8, 14).trimStart('0'), it.substring(14, 24)) }
            assertThat(invalid).isEmpty()
            numbers(db).values.filter { it.startsWith("CZ") }.forEach { assertThat(Iban.of(it).value).isEqualTo(it) }
        }
    }

    @Test
    fun `valid and foreign numbers are left alone`() {
        connection().use { db ->
            val after = numbers(db)
            assertThat(after[validPrefixValidBase]).isEqualTo(before[validPrefixValidBase])
            assertThat(after[collisionHolder]).isEqualTo(before[collisionHolder])
            assertThat(after[foreignIban]).isEqualTo(before[foreignIban])
            assertThat(versionOf(db, validPrefixValidBase)).isZero()
        }
    }

    @Test
    fun `an invalid prefix is dropped and the valid base kept`() {
        connection().use { db ->
            val after = numbers(db).getValue(invalidPrefixOnly)
            assertThat(after.substring(8, 14)).isEqualTo("000000")
            assertThat(after.substring(14)).isEqualTo(before.getValue(invalidPrefixOnly).substring(14))
        }
    }

    @Test
    fun `an invalid base is replaced keeping bank code and avoiding taken numbers`() {
        connection().use { db ->
            val after = numbers(db)
            for (id in listOf(invalidBaseByOne, collisionVictim)) {
                assertThat(after[id]).isNotEqualTo(before[id])
                assertThat(after.getValue(id).substring(4, 8)).isEqualTo("0000")
                assertThat(versionOf(db, id)).isEqualTo(1)
            }
            assertThat(after.values).doesNotHaveDuplicates()
        }
    }

    @Test
    fun `every re-issue leaves an audit row and an outbox event`() {
        connection().use { db ->
            val reissued = setOf(invalidBaseByOne, invalidPrefixOnly, collisionVictim)
            val audit = db.createStatement().executeQuery(
                "SELECT account_id, previous_account_number, new_account_number, reason FROM account_number_reissue",
            ).use { rs ->
                generateSequence { if (rs.next()) rs else null }.map {
                    Triple(it.getObject(1, UUID::class.java), it.getString(2), it.getString(3)) to it.getString(4)
                }.toList()
            }
            assertThat(audit.map { it.first.first }).containsExactlyInAnyOrderElementsOf(reissued)
            val after = numbers(db)
            audit.forEach { (row, reason) ->
                assertThat(row.second).isEqualTo(before[row.first])
                assertThat(row.third).isEqualTo(after[row.first])
                assertThat(reason).isEqualTo("CZ_MOD11_INVALID")
            }

            val events = db.createStatement().executeQuery(
                "SELECT aggregate_id, payload, status FROM account_outbox WHERE event_type = 'AccountNumberReissued'",
            ).use { rs ->
                generateSequence { if (rs.next()) rs else null }.map {
                    Triple(it.getObject(1, UUID::class.java), mapper.readTree(it.getString(2)), it.getString(3))
                }.toList()
            }
            assertThat(events.map { it.first }).containsExactlyInAnyOrderElementsOf(reissued)
            events.forEach { (id, body, status) ->
                assertThat(status).isEqualTo("PENDING")
                assertThat(body["eventType"].asText()).isEqualTo("AccountNumberReissued")
                assertThat(body["aggregateId"].asText()).isEqualTo(id.toString())
                assertThat(body["previousAccountNumber"].asText()).isEqualTo(before[id])
                assertThat(body["accountNumber"].asText()).isEqualTo(after[id])
                assertThat(body["version"].asLong()).isEqualTo(1)
            }
        }
    }

    private fun flyway(target: String) = Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations("filesystem:$migrations")
        .target(target)
        .load()

    private fun connection(): Connection =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun seed(db: Connection, id: UUID, number: String) {
        db.prepareStatement(
            "INSERT INTO accounts (id, account_number, account_type, party_id, product_id, currency_code) " +
                "VALUES (?, ?, 'CURRENT', ?, ?, 'CZK')",
        ).use {
            it.setObject(1, id)
            it.setString(2, number)
            it.setObject(3, UUID.randomUUID())
            it.setObject(4, UUID.randomUUID())
            it.executeUpdate()
        }
    }

    private fun numbers(db: Connection): Map<UUID, String> =
        db.createStatement().executeQuery("SELECT id, account_number FROM accounts").use { rs ->
            generateSequence { if (rs.next()) rs else null }
                .associate { it.getObject(1, UUID::class.java) to it.getString(2) }
        }

    private fun versionOf(db: Connection, id: UUID): Long =
        db.prepareStatement("SELECT version FROM accounts WHERE id = ?").use {
            it.setObject(1, id)
            it.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    private fun bumpLast(base: String) = base.dropLast(1) + ((base.last() - '0' + 1) % 10)

    private fun distinctValidBase(vararg avoid: String): String {
        while (true) {
            val b = CzechAccountNumber.generateBase()
            if (b !in avoid && b.take(9) !in avoid.map { it.take(9) }) return b
        }
    }

    private fun iban(bank: String, prefix: String, base: String): String {
        val bban = bank + prefix + base
        val check = 98 - BigInteger(bban + "123500").mod(BigInteger.valueOf(97)).toInt()
        return "CZ" + check.toString().padStart(2, '0') + bban
    }
}
