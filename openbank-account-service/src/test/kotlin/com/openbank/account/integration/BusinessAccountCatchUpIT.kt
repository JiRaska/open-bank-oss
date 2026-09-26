// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.integration

import com.openbank.account.application.port.out.DirectoryPage
import com.openbank.account.application.port.out.DirectoryParty
import com.openbank.account.application.port.out.PartyDirectoryPort
import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import io.agroal.api.AgroalDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deploy-order independence of the business current account: a business party that is ALREADY
 * ACTIVE (no ACTIVE event will ever arrive again) gets exactly one active business account from the
 * catch-up, driven by the REAL scheduler against a real Postgres. Only party-service is stubbed.
 *
 * The replay half: the same party's ACTIVE event is also delivered on the real consumer path while
 * the catch-up keeps ticking, so both paths race on one party. Both use one idempotency key and an
 * existing-CURRENT check, so the count after several further ticks must still be one.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_accounts_it")],
)
@QuarkusTestResource(BusinessAccountCatchUpIT.InMemoryPartyChannel::class)
@TestProfile(BusinessAccountCatchUpIT.CatchUpOn::class)
class BusinessAccountCatchUpIT {

    class InMemoryPartyChannel : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchIncomingChannelsToInMemory("party-events-in")

        override fun stop() = InMemoryConnector.clear()
    }

    class CatchUpOn : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            // `%test.quarkus.scheduler.enabled` is false here; this IT switches the real scheduler on.
            "quarkus.scheduler.enabled" to "true",
            "openbank.account.onboarding.open-business-accounts" to "true",
            "openbank.account.onboarding.business-catch-up.enabled" to "true",
            "openbank.account.onboarding.business-catch-up.interval" to "2s",
            "openbank.account.onboarding.business-catch-up.initial-delay" to "1s",
            // page-size 2: the fixture spans two pages, so pagination is part of the claim.
            "openbank.account.onboarding.business-catch-up.page-size" to "2",
            // Product validation is not under test; a refused connection fails open.
            "quarkus.rest-client.product-catalog-api.url" to "http://127.0.0.1:1",
            "openbank.outbox.dispatch-enabled" to "false",
        )

        override fun getEnabledAlternatives(): MutableSet<Class<*>> = mutableSetOf(StubPartyDirectory::class.java)
    }

    /** party-service's ACTIVE list. Literal ids: a profile loads in another classloader. */
    @Alternative
    @ApplicationScoped
    class StubPartyDirectory : PartyDirectoryPort {
        override suspend fun listActive(page: Int, size: Int): DirectoryPage {
            calls.incrementAndGet()
            return DirectoryPage(FIXTURE.drop(page * size).take(size), hasMore = (page + 1) * size < FIXTURE.size)
        }

        companion object {
            val calls = AtomicInteger(0)
        }
    }

    @Any
    @Inject
    lateinit var connector: InMemoryConnector

    @Inject
    lateinit var dataSource: AgroalDataSource

    private data class Row(val type: String, val status: String, val product: UUID, val currency: String)

    private fun accountsOf(partyId: UUID): List<Row> = dataSource.connection.use { c ->
        c.prepareStatement("SELECT account_type, status, product_id, currency_code FROM accounts WHERE party_id = ?")
            .use { ps ->
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

    @Test
    fun `a business party already ACTIVE before deploy gets exactly one active business account`() {
        val expected = Row("CURRENT", "ACTIVE", BUSINESS_PRODUCT, "EUR")
        assertThat(await { accountsOf(SOLE_TRADER) == listOf(expected) && accountsOf(COMPANY) == listOf(expected) })
            .describedAs("the real scheduler must open and activate the account (never = the tick aborted)")
            .isTrue()

        // Replay on the event path while the catch-up keeps running.
        val source = connector.source<String>("party-events-in").also { it.runOnVertxContext(true) }
        repeat(2) {
            source.send(
                """{"eventType":"KYC_STATUS_CHANGED","partyId":"$SOLE_TRADER","partyType":"SOLE_TRADER",""" +
                    """"status":"ACTIVE","legalName":"Catch-up IT","occurredAt":"2026-09-19T10:00:00Z"}""",
            )
        }
        val baseline = StubPartyDirectory.calls.get()
        assertThat(await { StubPartyDirectory.calls.get() >= baseline + REPEAT_CALLS }).isTrue()

        assertThat(accountsOf(SOLE_TRADER)).describedAs("one account, never two").containsExactly(expected)
        assertThat(accountsOf(COMPANY)).containsExactly(expected)
        assertThat(accountsOf(INDIVIDUAL)).describedAs("retail is not this job's").isEmpty()
        assertThat(accountsOf(PENDING_BUSINESS)).describedAs("not ACTIVE: nothing opened").isEmpty()
    }

    private companion object {
        val BUSINESS_PRODUCT: UUID = UUID.fromString("d4275d2a-1343-3052-a6c0-8a99149b6c62")
        val SOLE_TRADER: UUID = UUID.fromString("00000000-0000-4000-8000-00000000b001")
        val INDIVIDUAL: UUID = UUID.fromString("00000000-0000-4000-8000-00000000b002")
        val PENDING_BUSINESS: UUID = UUID.fromString("00000000-0000-4000-8000-00000000b003")
        val COMPANY: UUID = UUID.fromString("00000000-0000-4000-8000-00000000b004")

        val FIXTURE = listOf(
            DirectoryParty(SOLE_TRADER, "SOLE_TRADER", "ACTIVE", "Catch-up IT"),
            DirectoryParty(INDIVIDUAL, "INDIVIDUAL", "ACTIVE", "Catch-up IT"),
            DirectoryParty(PENDING_BUSINESS, "COMPANY", "PENDING_KYC", "Catch-up IT"),
            DirectoryParty(COMPANY, "COMPANY", "ACTIVE", "Catch-up IT"),
        )

        const val REPEAT_CALLS = 6
        const val ATTEMPTS = 240
        const val POLL_MILLIS = 250L
    }
}
