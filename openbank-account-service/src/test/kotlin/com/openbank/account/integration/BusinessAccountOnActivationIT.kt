// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.integration

import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import io.agroal.api.AgroalDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.smallrye.reactive.messaging.memory.InMemorySource
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A business party that becomes ACTIVE without a current account (created before account-service
 * opened business accounts) gets exactly one, through the real consumer, the real account service
 * and a real Postgres. Events arrive on the in-memory `party-events-in` channel on a Vert.x
 * context, exactly as the Kafka connector delivers them.
 *
 * Ordering makes the negative assertions safe: every event goes to the same channel, so once a
 * LATER party's account exists, every earlier event (including the replays) has been consumed.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_accounts_it")],
)
@QuarkusTestResource(BusinessAccountOnActivationIT.InMemoryPartyChannel::class)
@TestProfile(BusinessAccountOnActivationIT.BusinessOnboardingOn::class)
class BusinessAccountOnActivationIT {

    class InMemoryPartyChannel : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchIncomingChannelsToInMemory("party-events-in")

        override fun stop() = InMemoryConnector.clear()
    }

    class BusinessOnboardingOn : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.account.onboarding.open-business-accounts" to "true",
            // The default localhost:8104 answers whatever happens to listen there on the machine
            // running the build (measured: a foreign product-catalog replied CZK and every open
            // failed). Product validation is not under test; a refused connection fails open.
            "quarkus.rest-client.product-catalog-api.url" to "http://127.0.0.1:1",
        )
    }

    @Any
    @Inject
    lateinit var connector: InMemoryConnector

    @Inject
    lateinit var dataSource: AgroalDataSource

    private fun source(): InMemorySource<String> =
        connector.source<String>("party-events-in").also { it.runOnVertxContext(true) }

    private data class Row(val type: String, val status: String, val product: UUID, val currency: String)

    private fun accountsOf(partyId: UUID): List<Row> = dataSource.connection.use { c ->
        c.prepareStatement(
            "SELECT account_type, status, product_id, currency_code FROM accounts WHERE party_id = ?",
        ).use { ps ->
            ps.setObject(1, partyId)
            ps.executeQuery().use { rs ->
                generateSequence { if (rs.next()) rs else null }.map {
                    Row(it.getString(1), it.getString(2), it.getObject(3, UUID::class.java), it.getString(4))
                }.toList()
            }
        }
    }

    private fun await(ready: () -> Boolean): Boolean {
        repeat(ATTEMPTS) {
            if (ready()) return true
            Thread.sleep(POLL_MILLIS)
        }
        return ready()
    }

    private fun activeEvent(partyId: UUID, partyType: String, eventType: String = "KYC_STATUS_CHANGED") =
        """{"eventType":"$eventType","partyId":"$partyId","partyType":"$partyType","status":"ACTIVE",""" +
            """"kycStatus":"APPROVED","legalName":"Business IT","occurredAt":"2026-09-19T10:00:00Z"}"""

    @Test
    fun `an activated sole trader without a current account gets exactly one active business account`() {
        val soleTrader = UUID.randomUUID()
        val company = UUID.randomUUID()
        val individual = UUID.randomUUID()
        val source = source()

        source.send(activeEvent(soleTrader, "SOLE_TRADER"))
        // Replays of the activation, in both envelope types that carry status.
        source.send(activeEvent(soleTrader, "SOLE_TRADER"))
        source.send(activeEvent(soleTrader, "SOLE_TRADER", eventType = "PARTY_UPDATED"))
        // Retail must be untouched by this path.
        source.send(activeEvent(individual, "INDIVIDUAL"))
        // Barrier: a later party on the same channel.
        source.send(activeEvent(company, "COMPANY"))

        assertThat(await { accountsOf(company).isNotEmpty() })
            .describedAs("the barrier party's account must appear, or the consumer never ran")
            .isTrue()

        assertThat(accountsOf(soleTrader))
            .describedAs("one business CURRENT account, activated, on the business product — never two")
            .containsExactly(Row("CURRENT", "ACTIVE", BUSINESS_PRODUCT, "EUR"))
        assertThat(accountsOf(company)).containsExactly(Row("CURRENT", "ACTIVE", BUSINESS_PRODUCT, "EUR"))
        assertThat(accountsOf(individual))
            .describedAs("an individual's ACTIVE event opens nothing; retail accounts come from PARTY_CREATED")
            .isEmpty()
    }

    private companion object {
        val BUSINESS_PRODUCT: UUID = UUID.fromString("d4275d2a-1343-3052-a6c0-8a99149b6c62")
        const val ATTEMPTS = 120
        const val POLL_MILLIS = 250L
    }
}
