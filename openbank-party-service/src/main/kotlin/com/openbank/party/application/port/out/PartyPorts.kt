// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.port.out

import com.openbank.party.domain.model.Party
import com.openbank.party.domain.model.PartyChangeMateriality
import com.openbank.party.domain.model.PartyDocument
import com.openbank.party.domain.model.PartyDocumentFile
import com.openbank.party.domain.model.PartyEvent
import com.openbank.party.domain.model.PartyMandate
import com.openbank.party.domain.model.PartyStatus
import com.openbank.party.domain.model.Payee
import java.time.Instant
import java.util.UUID

/** Outbound persistence port for the party aggregate. */
interface PartyRepository {

    suspend fun save(party: Party): Party

    /**
     * Persists [party] AND [event] in one transaction (issue #4007). This — not a Kafka emitter —
     * is how a party lifecycle event leaves the service: the row and the `party_outbox` entry
     * commit together, or neither does, and the dispatcher relays the entry afterwards.
     *
     * The event-free [save] above stays for the paths that legitimately publish nothing (test
     * fixtures, pact provider states). Prefer this one for anything a consumer must hear about.
     */
    suspend fun save(party: Party, event: PartyEvent): Party

    suspend fun findById(id: UUID): Party?

    suspend fun findByEmail(email: String): Party?

    /** Pay-to-phone: parties that opted in AND whose phone hash is in [hashes]. */
    suspend fun findDiscoverableByPhoneHashes(hashes: Collection<String>): List<Party>

    /** Toggle pay-to-phone findability. Returns false when no such party exists. */
    suspend fun updateDiscoverable(partyId: UUID, discoverable: Boolean, at: Instant): Boolean

    suspend fun update(party: Party): Party

    /** Transactional-outbox counterpart of [update] — see [save] with a [PartyEvent]. */
    suspend fun update(party: Party, event: PartyEvent): Party

    suspend fun listAll(page: Int, size: Int): List<Party>

    /** Filter by [status]. Used by the onboarding cockpit funnel view (ADR-0068). */
    suspend fun listByStatus(status: PartyStatus, page: Int, size: Int): List<Party>

    /**
     * Bounded name search (ADR-0055): case-insensitive substring match over legal_name /
     * trading_name, keyset-paginated by partyId. [escapedTerm] is the LIKE-escaped term from
     * [com.openbank.libs.api.search.SearchRequest] (paired with `ESCAPE '\'`); the impl
     * lower-cases it and wraps it in `%…%`. Fetches up to [limit] rows after [afterId].
     */
    suspend fun searchByBusinessKeys(escapedTerm: String, limit: Int, afterId: UUID?): List<Party>

    suspend fun countAll(): Long

    /** Count parties in a given [status]. Used for funnel KPI tiles (ADR-0068). */
    suspend fun countByStatus(status: PartyStatus): Long

    /** GDPR Art. 17 erasure: anonymize the party's personal data in place. */
    suspend fun anonymize(id: UUID)

    /** Transactional-outbox counterpart of [anonymize] — see [save] with a [PartyEvent]. */
    suspend fun anonymize(id: UUID, event: PartyEvent)

    suspend fun findByKeycloakSub(sub: String): Party?

    /** ADR-0072: look up a party by pre-computed RČ blind index (exact match). */
    suspend fun findByRcBlindIndex(index: String): Party?

    /**
     * Scoped update of the marketing-consent projection (ADR-0205 D4) — a targeted UPDATE, not a
     * find-then-mutate-then-save of the whole [Party] aggregate, so it cannot clobber an unrelated
     * concurrent write to any other party field. No-op if the party row does not exist (the
     * consumer that calls this only has a partyId from an event, not a guarantee the party still
     * exists locally).
     */
    suspend fun updateMarketingConsentProjection(partyId: UUID, granted: Boolean, at: Instant)
}

/** Outbound persistence port for party identity documents. */
interface PartyDocumentRepository {

    suspend fun save(doc: PartyDocument): PartyDocument

    suspend fun findByPartyId(partyId: UUID): List<PartyDocument>
}

/** Outbound persistence port for saved payees (TOP-10 #5). */
interface PartyPayeeRepository {
    /** Upsert by (partyId, normalised iban) — a re-save of an existing IBAN updates that row. */
    suspend fun save(payee: Payee): Payee

    /** Newest first, matching the app's own display order. */
    suspend fun findByPartyId(partyId: UUID): List<Payee>

    suspend fun countByPartyId(partyId: UUID): Long

    /** No-op (not an error) if no such payee exists — delete is idempotent. */
    suspend fun deleteByPartyIdAndIban(partyId: UUID, iban: String)
}

/** Outbound persistence port for KYC document binary files. */
interface PartyDocumentFileRepository {
    suspend fun save(file: PartyDocumentFile): PartyDocumentFile
    suspend fun findByPartyId(partyId: UUID): List<PartyDocumentFile>

    /** Fetch by id, constrained to [partyId] to prevent cross-party reads. */
    suspend fun findByIdAndPartyId(id: UUID, partyId: UUID): PartyDocumentFile?

    /** GDPR Art. 17 — delete all document files for [partyId] as part of erasure. */
    suspend fun deleteByPartyId(partyId: UUID)
}

/**
 * Raised when a GDPR Art. 15 aggregation hop is rejected by the downstream's authorization layer
 * (401/403). Deliberately NOT folded into the best-effort degradation below: an auth failure means
 * the data subject's PII exists and we were refused it, which is categorically different from
 * "this party has no KYC case". Silently returning null there ships an export that is incomplete
 * and indistinguishable from a complete one — the caller must see a 502 instead.
 */
class GdprAggregationAuthException(service: String, status: Int) :
    RuntimeException("GDPR aggregation refused by $service: HTTP $status")

/**
 * Outbound port for GDPR Art. 15 aggregation: fetches PII from downstream services
 * on a best-effort basis (null = service unavailable, export proceeds with party PII only).
 *
 * Best-effort covers *absence* and *unavailability* only. An authorization rejection throws
 * [GdprAggregationAuthException] rather than degrading — see its KDoc.
 */
interface GdprAggregationPort {
    /** Returns the latest KYC case for [partyId], or null if absent/unavailable. */
    suspend fun fetchKycData(partyId: java.util.UUID): Map<String, Any?>?

    /** Returns all cards for [partyId], or empty list if absent/unavailable. */
    suspend fun fetchCardData(partyId: java.util.UUID): List<Map<String, Any?>>
}

/** consent-service refused or was unreachable — the toggle must not silently appear to succeed. */
class MarketingConsentForwardingException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Outbound port to consent-service for the marketing-consent forwarder (ADR-0198 D3, ADR-0205,
 * ADR-0206 D5). Unlike [GdprAggregationPort] this is a WRITE, not a best-effort read — a failure
 * here must propagate as [MarketingConsentForwardingException], never degrade silently, or the
 * caller would believe a toggle succeeded when consent-service never recorded it.
 *
 * `parties.consent_marketing` itself is never written here — [MarketingConsentProjectionService]
 * (ADR-0205 D4) is the sole writer, driven by consent-service's own outbox events, so the two
 * paths can never race each other into a split brain.
 */
interface MarketingConsentForwardingPort {
    /**
     * Grants the fixed internal marketing consent for [partyId] (ADR-0205 D3's
     * `party-service:marketing-comms` grantee, all three MARKETING_COMMS_* scopes). consent-service
     * auto-activates it synchronously (ADR-0205 D1's GDPR_ONLY_SCOPES path) — returns the new
     * consent's id, which [MarketingConsentTrackingRepository] does not yet know until the
     * ConsentGranted event round-trips through Kafka.
     */
    suspend fun grant(partyId: java.util.UUID): java.util.UUID

    /** Revokes [consentId] — must belong to [partyId] and the marketing grantee (server-checked). */
    suspend fun revoke(partyId: java.util.UUID, consentId: java.util.UUID, reason: String)
}

/**
 * ADR-0179: account-ownership guard for the merge precondition.
 *
 * Unlike [GdprAggregationPort] this is **fail-closed**: an unreachable account-service must abort
 * the merge, never allow it. Merging a party that still owns a funded account would strand the
 * balance on a retired identity — account closure does not check the balance
 * (ADR-0109 option B), so nothing downstream would catch it.
 */
interface PartyAccountGuardPort {
    /**
     * Returns the ids of accounts owned by [partyId] that are not yet CLOSED, or throws if the
     * answer cannot be established. An empty list is the only result that permits a merge.
     */
    suspend fun findOpenAccounts(partyId: UUID): List<String>
}

// The `PartyEventPublisher` port that used to live here — a bare `@Channel("party-events-out")`
// emitter fired after the repository had already committed — was removed with issue #4007. Party
// events are now written to `party_outbox` inside the state-change transaction (see
// [PartyRepository.save] with a [PartyEvent]) and relayed by `PartyOutboxDispatcher`. Two
// publishers on the same topic would race, and only one of them can be atomic.

/**
 * Counts party master-data changes by their materiality classification (ADR-0256 D1, #4458).
 *
 * A port rather than a direct `MeterRegistry` call so the use case stays framework-free, and a
 * dedicated counter rather than a flag on an existing one because the whole point of the
 * classification is that its three outcomes are separable: an environment where every update is
 * `NO_CHANGE`, and one where a name edit path exists but never fires `MATERIAL`, look identical
 * on any success/failure metric and different on this one.
 */
interface PartyChangeMetricsPort {
    fun changeClassified(materiality: PartyChangeMateriality)
}

/** Outbound persistence port for representation mandates (ADR-0284 D3). */
interface PartyMandateRepository {
    /** Row + outbox event in ONE transaction; an ACTIVE (principal, agent, role) triple is upserted, never duplicated. */
    suspend fun save(mandate: PartyMandate, event: PartyEvent): PartyMandate

    suspend fun update(mandate: PartyMandate, event: PartyEvent): PartyMandate

    suspend fun findById(id: UUID): PartyMandate?

    suspend fun findByPrincipal(principalPartyId: UUID): List<PartyMandate>

    suspend fun findByAgent(agentPartyId: UUID): List<PartyMandate>

    suspend fun findActive(principalPartyId: UUID, agentPartyId: UUID, role: String): PartyMandate?
}
