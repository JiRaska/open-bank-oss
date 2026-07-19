// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.port.out

import com.openbank.party.domain.model.Party
import com.openbank.party.domain.model.PartyDocument
import com.openbank.party.domain.model.PartyDocumentFile
import com.openbank.party.domain.model.PartyStatus
import java.util.UUID

/** Outbound persistence port for the party aggregate. */
interface PartyRepository {

    suspend fun save(party: Party): Party

    suspend fun findById(id: UUID): Party?

    suspend fun findByEmail(email: String): Party?

    suspend fun update(party: Party): Party

    suspend fun listAll(page: Int, size: Int): List<Party>

    /** Filter by [status]. Used by the onboarding cockpit funnel view (ADR-0068). */
    suspend fun listByStatus(status: PartyStatus, page: Int, size: Int): List<Party>

    /**
     * Bounded name search (ADR-0055): case-insensitive substring match over legal_name /
     * trading_name, keyset-paginated by partyId. [escapedTerm] is the LIKE-escaped term from
     * [com.openbank.libs.api.search.SearchRequest] (paired with `ESCAPE '\'`); the impl
     * lower-cases it and wraps it in `%…%`. Fetches up to [limit] rows after [afterId].
     */
    suspend fun searchByName(escapedTerm: String, limit: Int, afterId: UUID?): List<Party>

    suspend fun countAll(): Long

    /** Count parties in a given [status]. Used for funnel KPI tiles (ADR-0068). */
    suspend fun countByStatus(status: PartyStatus): Long

    /** GDPR Art. 17 erasure: anonymize the party's personal data in place. */
    suspend fun anonymize(id: UUID)

    suspend fun findByKeycloakSub(sub: String): Party?

    /** ADR-0072: look up a party by pre-computed RČ blind index (exact match). */
    suspend fun findByRcBlindIndex(index: String): Party?
}

/** Outbound persistence port for party identity documents. */
interface PartyDocumentRepository {

    suspend fun save(doc: PartyDocument): PartyDocument

    suspend fun findByPartyId(partyId: UUID): List<PartyDocument>
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

/** Outbound port for party domain events. */
interface PartyEventPublisher {

    suspend fun publishPartyCreated(party: Party)

    suspend fun publishPartyUpdated(party: Party)

    suspend fun publishKycStatusChanged(party: Party)

    suspend fun publishPartyErased(id: UUID)
}
