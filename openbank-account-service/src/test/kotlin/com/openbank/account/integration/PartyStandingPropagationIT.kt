// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.integration

import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import io.agroal.api.AgroalDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.smallrye.reactive.messaging.memory.InMemorySource
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * ADR-0374 D4 by effect: a party that loses its standing can no longer debit its accounts, and the
 * rails can read that off the account lookup they already call.
 *
 * Real HTTP for opening, freezing and reading accounts, a real Postgres, and the real
 * `PartyEventConsumer`: events arrive on the in-memory `party-events-in` channel on a Vert.x
 * context, exactly as the Kafka connector delivers them.
 *
 * Every event goes to one channel and is consumed in order, so once a LATER event's effect is
 * visible every earlier one has been applied. The "nothing changed" assertions rest on that
 * barrier, never on a sleep.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_accounts_it")],
)
@QuarkusTestResource(PartyStandingPropagationIT.InMemoryPartyChannel::class)
class PartyStandingPropagationIT {

    class InMemoryPartyChannel : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchIncomingChannelsToInMemory("party-events-in")

        override fun stop() = InMemoryConnector.clear()
    }

    @Any
    @Inject
    lateinit var connector: InMemoryConnector

    @Inject
    lateinit var dataSource: AgroalDataSource

    private fun source(): InMemorySource<String> =
        connector.source<String>("party-events-in").also { it.runOnVertxContext(true) }

    private data class Row(val status: String, val freezeOrigin: String?, val version: Long)

    private fun row(accountId: String): Row = dataSource.connection.use { c ->
        c.prepareStatement("SELECT status, freeze_origin, version FROM accounts WHERE id = ?").use { ps ->
            ps.setObject(1, UUID.fromString(accountId))
            ps.executeQuery().use { rs ->
                check(rs.next()) { "account $accountId not found" }
                Row(rs.getString(1), rs.getString(2), rs.getLong(3))
            }
        }
    }

    private fun await(what: String, ready: () -> Boolean) {
        repeat(ATTEMPTS) {
            if (ready()) return
            Thread.sleep(POLL_MILLIS)
        }
        assertThat(ready()).describedAs(what).isTrue()
    }

    private fun statusEvent(partyId: UUID, status: String, eventType: String = "KYC_STATUS_CHANGED") =
        """{"eventType":"$eventType","partyId":"$partyId","partyType":"INDIVIDUAL","status":"$status",""" +
            """"legalName":"Standing IT","occurredAt":"2026-11-11T10:00:00Z"}"""

    private fun openActiveAccount(partyId: UUID): String = given()
        .contentType("application/json")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .body(
            """{"partyId":"$partyId","productId":"$PRODUCT","accountType":"CURRENT",""" +
                """"currencyCode":"CZK","legalName":"Standing IT"}""",
        )
        .post("/api/v1/accounts")
        .then().statusCode(201)
        .extract().jsonPath().getString("id")

    private fun operatorFreeze(accountId: String) {
        given().contentType("application/json").body("""{"reason":"operator review"}""")
            .post("/api/v1/accounts/$accountId/freeze")
            .then().statusCode(200)
    }

    private data class Standing(val status: String, val mayDebit: Boolean, val mayCredit: Boolean, val reason: String?)

    private fun standingOverHttp(accountId: String): Standing {
        val json = given().get("/api/v1/accounts/$accountId").then().statusCode(200).extract().jsonPath()
        return Standing(
            status = json.getString("status"),
            mayDebit = json.getBoolean("standing.mayDebit"),
            mayCredit = json.getBoolean("standing.mayCredit"),
            reason = json.getString("standing.reason"),
        )
    }

    /** Suspends a fresh party and waits for its account to freeze; everything sent before it has been consumed. */
    private fun barrier(source: InMemorySource<String>) {
        val party = UUID.randomUUID()
        val account = openActiveAccount(party)
        source.send(statusEvent(party, "SUSPENDED"))
        await("the barrier party's account must freeze, or the consumer never ran") {
            row(account).status == "FROZEN"
        }
    }

    @Test
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `a suspended party cannot debit and good standing gives the accounts back`() {
        val party = UUID.randomUUID()
        val bystander = UUID.randomUUID()
        val first = openActiveAccount(party)
        val second = openActiveAccount(party)
        val untouched = openActiveAccount(bystander)
        val source = source()

        assertThat(
            standingOverHttp(first),
        ).isEqualTo(Standing("ACTIVE", mayDebit = true, mayCredit = true, reason = null))

        source.send(statusEvent(party, "SUSPENDED"))
        await("both accounts of the suspended party must freeze") {
            row(first).status == "FROZEN" && row(second).status == "FROZEN"
        }
        assertThat(row(first).freezeOrigin).isEqualTo("PARTY_STANDING")
        assertThat(standingOverHttp(first))
            .isEqualTo(Standing("FROZEN", mayDebit = false, mayCredit = false, reason = "ACCOUNT_FROZEN"))
        assertThat(standingOverHttp(second).mayDebit).isFalse()
        assertThat(row(untouched)).describedAs("another party's account is not touched")
            .extracting(Row::status).isEqualTo("ACTIVE")

        // A redelivery, on the other envelope that carries status, writes nothing.
        val frozenVersion = row(first).version
        source.send(statusEvent(party, "SUSPENDED", eventType = "PARTY_UPDATED"))
        barrier(source)
        assertThat(row(first).version).isEqualTo(frozenVersion)

        source.send(statusEvent(party, "ACTIVE"))
        await("good standing must lift the party freeze") {
            row(first).status == "ACTIVE" &&
                row(second).status == "ACTIVE"
        }
        assertThat(row(first).freezeOrigin).isNull()
        assertThat(
            standingOverHttp(first),
        ).isEqualTo(Standing("ACTIVE", mayDebit = true, mayCredit = true, reason = null))
    }

    @Test
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `a closed party's accounts freeze too`() {
        val party = UUID.randomUUID()
        val account = openActiveAccount(party)

        source().send(statusEvent(party, "CLOSED", eventType = "PARTY_UPDATED"))

        await("the closed party's account must freeze") { row(account).status == "FROZEN" }
        assertThat(standingOverHttp(account).mayDebit).isFalse()
    }

    @Test
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `good standing never lifts a freeze an operator placed`() {
        val party = UUID.randomUUID()
        val frozenByOperator = openActiveAccount(party)
        val takenOver = openActiveAccount(party)
        val source = source()
        operatorFreeze(frozenByOperator)

        source.send(statusEvent(party, "SUSPENDED"))
        await("the party freeze must land on the still-active account") { row(takenOver).status == "FROZEN" }
        assertThat(row(frozenByOperator).freezeOrigin).isEqualTo("OPERATOR")
        assertThat(row(takenOver).freezeOrigin).isEqualTo("PARTY_STANDING")

        // An operator freezes the party-frozen account as well: the freeze becomes theirs.
        operatorFreeze(takenOver)
        assertThat(row(takenOver).freezeOrigin).isEqualTo("OPERATOR")

        source.send(statusEvent(party, "ACTIVE"))
        barrier(source)

        assertThat(row(frozenByOperator).status).isEqualTo("FROZEN")
        assertThat(row(takenOver).status).isEqualTo("FROZEN")
        assertThat(standingOverHttp(takenOver).mayDebit).isFalse()
    }

    private companion object {
        const val OPERATOR = "00000000-0000-0000-0000-000000000099"
        const val PRODUCT = "00000000-2222-0000-0000-000000000001"
        const val ATTEMPTS = 120
        const val POLL_MILLIS = 250L
    }
}
