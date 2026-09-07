// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.reconcile

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.openbank.analytics.application.port.out.DurableBackfillUnavailableException
import com.openbank.libs.analytics.BackfillRequest
import com.openbank.libs.analytics.BackfillWindow
import com.openbank.libs.analytics.IngestSource
import io.smallrye.mutiny.Uni
import jakarta.enterprise.inject.Vetoed
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class AccountInitialLoadSourceTest {

    private val mapper = ObjectMapper().registerModule(JavaTimeModule())

    // Vetoed: it implements a @RegisterRestClient interface, so ArC would otherwise treat it as a
    // bean and try to inject its List constructor parameter.
    @Vetoed
    private class FakeRegistry(private val pages: List<AccountRegistryPage>) : AccountRegistryClient {
        var calls: Int = 0

        override fun listActive(limit: Int, cursor: String?): Uni<AccountRegistryPage> {
            val page = pages[calls]
            calls++
            return Uni.createFrom().item(page)
        }
    }

    private fun entry(id: UUID, party: UUID, openedAt: String) = AccountRegistryEntry(
        id = id,
        accountNumber = "CZ6508000000192000145399",
        accountType = "CURRENT",
        partyId = party,
        productId = "prod-1",
        currencyCode = "CZK",
        openedAt = Instant.parse(openedAt),
    )

    private fun source(registry: AccountRegistryClient): AccountInitialLoadSource = AccountInitialLoadSource().also {
        it.registry = registry
        it.objectMapper = mapper
    }

    private fun request(
        src: IngestSource = IngestSource.INITIAL_LOAD,
        aggregateType: String? = null,
        aggregateId: String? = null,
    ) = BackfillRequest(
        source = src,
        from = Instant.parse("2026-01-01T00:00:00Z"),
        to = Instant.parse("2026-12-31T00:00:00Z"),
        aggregateType = aggregateType,
        aggregateId = aggregateId,
        reason = "seed pre-stream accounts",
        requestedBy = "operator",
    )

    private val window =
        BackfillWindow(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-12-31T00:00:00Z"))

    @Test
    fun `projects one creation event per active account, carrying the owner`() {
        val id = UUID.randomUUID()
        val party = UUID.randomUUID()
        val registry = FakeRegistry(listOf(AccountRegistryPage(listOf(entry(id, party, "2026-06-01T10:00:00Z")))))

        val out = runBlocking { source(registry).read(window, request()) }

        assertEquals(1, out.size)
        val node = mapper.readTree(out.single())
        assertEquals("AccountCreated", node["eventType"].asText())
        assertEquals("ACCOUNT", node["aggregateType"].asText())
        assertEquals(id.toString(), node["aggregateId"].asText())
        assertEquals(party.toString(), node["partyId"].asText())
        // The API spells it currencyCode and the event spells it currency. A same-name coincidence
        // would leave this empty and every downstream row would look merely unremarkable.
        assertEquals("CZK", node["currency"].asText())
        // Business time, not load time: a seeded account must land in the period it was opened.
        assertEquals("2026-06-01T10:00:00Z", node["occurredAt"].asText())
    }

    /**
     * The load-bearing one. BackfillService de-duplicates by event id and bronze is a
     * ReplacingMergeTree ordered by (aggregate_type, aggregate_id, event_id), so a random id makes
     * every re-run insert a second row per account — and a re-run is how an operator recovers a
     * half-failed load.
     */
    @Test
    fun `the projected event id is derived from the account, so a re-run is idempotent`() {
        val id = UUID.randomUUID()
        val party = UUID.randomUUID()

        fun once(): String {
            val registry =
                FakeRegistry(listOf(AccountRegistryPage(listOf(entry(id, party, "2026-06-01T10:00:00Z")))))
            val json = runBlocking { source(registry).read(window, request()) }.single()
            return mapper.readTree(json)["eventId"].asText()
        }
        assertEquals(once(), once())
    }

    @Test
    fun `two accounts get two different event ids`() {
        val party = UUID.randomUUID()
        val registry =
            FakeRegistry(
                listOf(
                    AccountRegistryPage(
                        listOf(
                            entry(UUID.randomUUID(), party, "2026-06-01T10:00:00Z"),
                            entry(UUID.randomUUID(), party, "2026-06-02T10:00:00Z"),
                        ),
                    ),
                ),
            )
        val ids =
            runBlocking { source(registry).read(window, request()) }
                .map { mapper.readTree(it)["eventId"].asText() }
        assertEquals(2, ids.toSet().size)
    }

    @Test
    fun `follows the cursor until the sweep terminates`() {
        val party = UUID.randomUUID()
        val registry =
            FakeRegistry(
                listOf(
                    AccountRegistryPage(
                        listOf(entry(UUID.randomUUID(), party, "2026-06-01T10:00:00Z")),
                        AccountRegistryPagination("next"),
                    ),
                    AccountRegistryPage(listOf(entry(UUID.randomUUID(), party, "2026-06-02T10:00:00Z"))),
                ),
            )
        assertEquals(2, runBlocking { source(registry).read(window, request()) }.size)
        assertEquals(2, registry.calls)
    }

    /** An account opened outside the requested window is not this run's business. */
    @Test
    fun `an account opened outside the window is excluded`() {
        val registry =
            FakeRegistry(
                listOf(
                    AccountRegistryPage(
                        listOf(entry(UUID.randomUUID(), UUID.randomUUID(), "2025-01-01T10:00:00Z")),
                    ),
                ),
            )
        assertTrue(runBlocking { source(registry).read(window, request()) }.isEmpty())
    }

    /**
     * Every other source still fails closed. BACKFILL needs the durable outbox and no reader is
     * wired; returning nothing would produce a green COMPLETED report over an unrecovered gap.
     */
    @Test
    fun `a non-initial-load request still fails closed`() {
        val registry = FakeRegistry(listOf(AccountRegistryPage(emptyList())))
        assertThrows<DurableBackfillUnavailableException> {
            runBlocking { source(registry).read(window, request(src = IngestSource.BACKFILL)) }
        }
    }

    @Test
    fun `a request narrowed to another aggregate type yields nothing and calls no endpoint`() {
        val registry = FakeRegistry(listOf(AccountRegistryPage(emptyList())))
        assertTrue(runBlocking { source(registry).read(window, request(aggregateType = "TRANSACTION")) }.isEmpty())
        assertEquals(0, registry.calls)
    }

    @Test
    fun `a request narrowed to one account seeds only that account`() {
        val wanted = UUID.randomUUID()
        val party = UUID.randomUUID()
        val registry =
            FakeRegistry(
                listOf(
                    AccountRegistryPage(
                        listOf(
                            entry(wanted, party, "2026-06-01T10:00:00Z"),
                            entry(UUID.randomUUID(), party, "2026-06-02T10:00:00Z"),
                        ),
                    ),
                ),
            )
        val out = runBlocking { source(registry).read(window, request(aggregateId = wanted.toString())) }
        assertEquals(1, out.size)
        assertEquals(wanted.toString(), mapper.readTree(out.single())["aggregateId"].asText())
    }
}
