// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.integration

import com.openbank.account.domain.model.CzechIbanValidity
import com.openbank.libs.domain.account.CzechAccountNumber
import com.openbank.libs.domain.account.Iban
import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.util.UUID
import javax.sql.DataSource

/**
 * V35: drives the REAL scheduled job (not a direct call, which would supply the Vert.x context the
 * scheduler does not) against a real database. A directly inserted account with a CZ number failing
 * mod-11 must end up with a valid number, one `account_number_reissue` audit row old -> new, and one
 * `AccountNumberReissued` outbox row written by the SAME transaction (equal `xmin`) as the account row.
 *
 * Dispatch is off so the outbox row is not UPDATEd (a new `xmin`) under the assertion. All values are
 * literals: a profile loads in another classloader (see the fleet CLAUDE.md note).
 */
@QuarkusTest
@TestProfile(AccountNumberReissueIT.Profile::class)
@QuarkusTestResource(
    value = PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_accounts_it")],
)
@QuarkusTestResource(AccountNumberReissueIT.DelegationChannel::class)
class AccountNumberReissueIT {

    class Profile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.outbox.dispatch-enabled" to "false",
            "openbank.account.number-reissue.enabled" to "true",
            "openbank.account.number-reissue.interval" to "1s",
            "openbank.account.number-reissue.initial-delay" to "1s",
        )
    }

    class DelegationChannel : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchIncomingChannelsToInMemory("delegation-events-in")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var dataSource: DataSource

    @Test
    fun `the scheduled job re-issues an invalid number atomically with its audit row and event`() {
        val accountId = UUID.randomUUID()
        val invalid = "CZ0000000000000000000002"
        insertAccount(accountId, invalid)

        val deadline = System.currentTimeMillis() + 30_000
        var current = invalid
        while (current == invalid && System.currentTimeMillis() < deadline) {
            Thread.sleep(500)
            current = scalar("SELECT account_number FROM accounts WHERE id = ?", accountId) as String
        }

        assertThat(current).describedAs("the job never re-issued the number").isNotEqualTo(invalid)
        assertThat(CzechIbanValidity.isNationallyValid(current)).isTrue()
        val audit = row(
            "SELECT old_account_number, new_account_number, reason FROM account_number_reissue WHERE account_id = ?",
            accountId,
        )
        assertThat(audit).containsExactly(invalid, current, "CZ_MOD11_INVALID")

        val xmins = row(
            "SELECT a.xmin::text, o.xmin::text, o.event_type FROM accounts a " +
                "JOIN account_outbox o ON o.aggregate_id = a.id WHERE a.id = ? AND o.event_type = 'AccountNumberReissued'",
            accountId,
        )
        assertThat(xmins[2]).isEqualTo("AccountNumberReissued")
        assertThat(xmins[1])
            .describedAs("account row and outbox row must be written by one transaction")
            .isEqualTo(xmins[0])
        assertThat(scalar("SELECT count(*) FROM account_number_reissue WHERE account_id = ?", accountId)).isEqualTo(1L)
    }

    /**
     * Same job, independent oracle: validity here is [CzechAccountNumber] applied to the stored prefix and
     * base, not the service's own [CzechIbanValidity], so a wrong weight in the service cannot grade itself.
     * Covers a bad PREFIX over a good base (the shape the old generator never produced but a hand-keyed row
     * can), and proves a valid number is left exactly as it was.
     */
    @Test
    fun `an invalid prefix is re-issued by the real job and a valid number is left alone`() {
        val validBase = CzechAccountNumber.generateBase()
        val valid = iban("0000", "000000", validBase)
        val prefixOnly = iban("0000", "000001", CzechAccountNumber.generateBase()) // prefix weight 1 -> sum 1
        val validId = UUID.randomUUID()
        val prefixId = UUID.randomUUID()
        insertAccount(validId, valid)
        insertAccount(prefixId, prefixOnly)
        assertThat(CzechAccountNumber.isValid("000001", prefixOnly.substring(14))).isFalse()

        val deadline = System.currentTimeMillis() + 30_000
        var current = prefixOnly
        while (current == prefixOnly && System.currentTimeMillis() < deadline) {
            Thread.sleep(500)
            current = scalar("SELECT account_number FROM accounts WHERE id = ?", prefixId) as String
        }

        assertThat(current).isNotEqualTo(prefixOnly)
        assertThat(CzechAccountNumber.isValid(current.substring(8, 14).trimStart('0'), current.substring(14, 24)))
            .describedAs("independent oracle on the re-issued number %s", current).isTrue()
        assertThat(Iban.of(current).value).isEqualTo(current)
        assertThat(scalar("SELECT account_number FROM accounts WHERE id = ?", validId)).isEqualTo(valid)
        assertThat(scalar("SELECT version FROM accounts WHERE id = ?", validId)).isEqualTo(0L)
        assertThat(scalar("SELECT count(*) FROM account_number_reissue WHERE account_id = ?", validId)).isEqualTo(0L)
    }

    private fun iban(bank: String, prefix: String, base: String): String {
        val bban = bank + prefix + base
        val check = 98 - BigInteger(bban + "123500").mod(BigInteger.valueOf(97)).toInt()
        return "CZ" + check.toString().padStart(2, '0') + bban
    }

    private fun insertAccount(id: UUID, number: String) {
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO accounts (id, account_number, account_type, party_id, product_id, currency_code) " +
                    "VALUES (?, ?, 'CURRENT', ?, ?, 'CZK')",
            ).use {
                it.setObject(1, id)
                it.setString(2, number)
                it.setObject(3, UUID.randomUUID())
                it.setObject(4, UUID.randomUUID())
                assertThat(it.executeUpdate()).isEqualTo(1)
            }
        }
    }

    private fun scalar(sql: String, id: UUID): Any? = dataSource.connection.use { c ->
        c.prepareStatement(sql).use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getObject(1)
            }
        }
    }

    private fun row(sql: String, id: UUID): List<String> = dataSource.connection.use { c ->
        c.prepareStatement(sql).use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "no row for $sql" }
                (1..rs.metaData.columnCount).map { rs.getString(it) }
            }
        }
    }
}
