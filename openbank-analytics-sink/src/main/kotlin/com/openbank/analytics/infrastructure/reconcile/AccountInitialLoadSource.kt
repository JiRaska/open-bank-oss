// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.reconcile

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.analytics.application.port.out.BackfillSource
import com.openbank.analytics.application.port.out.DurableBackfillUnavailableException
import com.openbank.libs.analytics.BackfillRequest
import com.openbank.libs.analytics.BackfillWindow
import com.openbank.libs.analytics.IngestSource
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * The [IngestSource.INITIAL_LOAD] adapter: seeds the warehouse with accounts that existed before
 * the event stream was switched on.
 *
 * WHY THIS EXISTS, MEASURED RATHER THAN ASSUMED (issue #8792, #2891). The account-to-party key is
 * the isolation boundary every party-scoped view rests on, and `silver_party_accounts` derives it
 * from `AccountCreated`. Measured 2026-09-05: account-service holds 88 accounts and every one of
 * them has a `party_id`, while the warehouse holds ownership for 19 — exactly the 19 opened after
 * bronze's first row on 2026-07-24. The producer is not broken and no payload field is missing:
 * 19 of 19 accounts opened during ingestion published a creation event and it was ingested.
 *
 * THE DIAGNOSIS IN #2891 IS THEREFORE WRONG IN KIND, NOT DEGREE. Ownership is a STATE fact modelled
 * as a CREATION event, so it exists only for aggregates created after the log began. No producer
 * change reaches the other 69 — they will never emit a creation event again — and the consequence is
 * not abstract: one unmapped account appears in 44 transactions and is invisible to Customer 360,
 * to every segment and to any Lipa earn evaluation, while every one of those reads succeeds.
 *
 * WHAT THIS ADAPTER DOES NOT DO. It never republishes to Kafka. `BackfillService` writes the mapped
 * envelopes straight to the sink, so `AccountCreated` — a discriminator read verbatim by
 * balance-service's `BalanceInitConsumer`, document-service's `AccountCreatedConsumer`,
 * statement-service's `AccountRegistryConsumer` and campaign-service's catalogs — never reaches
 * them. A backfill that republished would re-create balances and re-issue documents for 66 accounts.
 *
 * It also stays fail-closed for every other source. `BACKFILL` and `CORRECTION` need the durable
 * outbox and no such reader is wired, so a request for either still raises
 * [DurableBackfillUnavailableException] rather than quietly returning nothing — an empty result is
 * not a successful backfill.
 *
 * COVERAGE IS PARTIAL AND SAYS SO. The registry sweep enumerates ACTIVE accounts only, which was
 * 85 of 88 at the time of writing; the 3 `PENDING_ACTIVATION` accounts are not reachable through it
 * and stay unmapped. Widening the sweep is account-service's decision, not something to work around
 * from here, and the run's report carries the count so the shortfall is a number rather than a
 * silence.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class AccountInitialLoadSource : BackfillSource {

    @Inject
    @RestClient
    lateinit var registry: AccountRegistryClient

    @Inject
    lateinit var objectMapper: ObjectMapper

    private val log = Logger.getLogger(AccountInitialLoadSource::class.java)

    override suspend fun read(window: BackfillWindow, request: BackfillRequest): List<String> {
        if (request.source != IngestSource.INITIAL_LOAD) throw DurableBackfillUnavailableException()
        // A request narrowed to another aggregate type is not an error and not this adapter's work.
        val wanted = request.aggregateType?.uppercase()
        if (wanted != null && wanted != ACCOUNT_AGGREGATE) return emptyList()

        val accounts = mutableListOf<AccountRegistryEntry>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = registry.listActive(PAGE_SIZE, cursor).awaitSuspending()
            accounts += page.data
            cursor = page.pagination?.nextCursor
            pages++
        } while (cursor != null && pages < MAX_PAGES)

        if (cursor != null) {
            // Refusing beats silently seeding a prefix: a partial initial load looks exactly like a
            // complete one in every downstream view, and the missing accounts read as "no such
            // customer" rather than as an incomplete run.
            throw IllegalStateException(
                "account registry sweep did not terminate within $MAX_PAGES pages of $PAGE_SIZE; " +
                    "refusing to seed a partial initial load",
            )
        }

        val selected = accounts.filter { entry ->
            (request.aggregateId == null || request.aggregateId == entry.id.toString()) &&
                !entry.openedAt.isBefore(window.from) &&
                !entry.openedAt.isAfter(window.to)
        }
        log.infof(
            "account INITIAL_LOAD projection: %d active account(s) swept, %d within [%s, %s]",
            accounts.size,
            selected.size,
            window.from,
            window.to,
        )
        return selected.map(::projectCreationEvent)
    }

    /**
     * Projects one account's CURRENT registry state as the creation event it would have emitted.
     *
     * The event id is DERIVED from the account id, never random. `BackfillService` de-duplicates by
     * event id and bronze is a ReplacingMergeTree ordered by (aggregate_type, aggregate_id,
     * event_id), so a random id would make every re-run insert a second row per account instead of
     * collapsing onto the first — and a re-run is the normal way an operator recovers a half-failed
     * load. [UUID.nameUUIDFromBytes] is a name-based (v3) UUID: chosen for determinism, and nothing
     * here depends on it being unpredictable.
     *
     * `occurredAt` is the account's real `openedAt`, so a seeded account lands in the period it was
     * actually opened. Stamping the load time would put 69 accounts on one day and quietly rewrite
     * every cohort and funnel that reads bronze by business time.
     */
    private fun projectCreationEvent(entry: AccountRegistryEntry): String {
        val payload = linkedMapOf<String, Any>(
            "eventId" to deterministicEventId(entry.id).toString(),
            "eventType" to ACCOUNT_CREATED,
            "aggregateId" to entry.id.toString(),
            "aggregateType" to ACCOUNT_AGGREGATE,
            "version" to 0L,
            "accountNumber" to entry.accountNumber,
            "accountType" to entry.accountType,
            "partyId" to entry.partyId.toString(),
            "productId" to entry.productId,
            "currency" to entry.currencyCode,
            "occurredAt" to entry.openedAt.toString(),
            "sourceService" to SOURCE_SERVICE,
        )
        return objectMapper.writeValueAsString(payload)
    }

    private fun deterministicEventId(accountId: UUID): UUID =
        UUID.nameUUIDFromBytes("$EVENT_ID_NAMESPACE$accountId".toByteArray(StandardCharsets.UTF_8))

    companion object {
        const val ACCOUNT_AGGREGATE: String = "ACCOUNT"
        const val ACCOUNT_CREATED: String = "AccountCreated"
        const val SOURCE_SERVICE: String = "account-service"

        /** Namespace so a projected id can never collide with a real event id by construction. */
        const val EVENT_ID_NAMESPACE: String = "openbank:analytics:initial-load:account:"

        private const val PAGE_SIZE: Int = 200
        private const val MAX_PAGES: Int = 500
    }
}
