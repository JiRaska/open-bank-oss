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
 * Outbound port for GDPR Art. 15 aggregation: fetches PII from downstream services
 * on a best-effort basis (null = service unavailable, export proceeds with party PII only).
 */
interface GdprAggregationPort {
    /** Returns the latest KYC case for [partyId], or null if unavailable. */
    suspend fun fetchKycData(partyId: java.util.UUID): Map<String, Any?>?

    /** Returns all cards for [partyId], or empty list if unavailable. */
    suspend fun fetchCardData(partyId: java.util.UUID): List<Map<String, Any?>>
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

/** Outbound port for party domain events. */
interface PartyEventPublisher {

    suspend fun publishPartyCreated(party: Party)

    suspend fun publishPartyUpdated(party: Party)

    suspend fun publishKycStatusChanged(party: Party)

    suspend fun publishPartyErased(id: UUID)

    /**
     * ADR-0179: [merged] is the retired duplicate (status MERGED); [survivingPartyId] is the party
     * consumers should follow from now on. Deliberately NOT PARTY_ERASED — nothing was anonymized
     * and no subject-rights request occurred.
     */
    suspend fun publishPartyMerged(merged: Party, survivingPartyId: UUID)
}
