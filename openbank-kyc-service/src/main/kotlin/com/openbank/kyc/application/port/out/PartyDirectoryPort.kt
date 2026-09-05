// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.application.port.out

import java.time.Instant
import java.util.UUID

/**
 * Read-only outbound view of party-service's party register (ADR-0002 hexagonal architecture).
 *
 * This exists for exactly one caller — [com.openbank.kyc.application.OrphanedPartyDetector] — and
 * deliberately exposes the *minimum* a reconciliation needs: which parties exist, when they were
 * created, and what lifecycle state they are in. It is not a general party client, and it must not
 * grow into one: kyc-service holds one side of the invariant and party-service holds the other, so
 * the only legitimate traffic across this port is the enumeration that lets the two be compared.
 *
 * Implemented by [com.openbank.kyc.infrastructure.client.PartyDirectoryAdapter].
 */
interface PartyDirectoryPort {

    /**
     * One page of the party register. Ordering is NOT guaranteed — party-service's
     * `GET /api/v1/parties` is offset-paginated over an unordered scan, so a page boundary can
     * shift under a concurrent insert. That is tolerable here and nowhere else: a party missed
     * because it moved across a boundary is picked up by the next run, and a party seen twice is
     * deduplicated by the id set the detector builds.
     *
     * @param page zero-based page index
     * @param size page size; party-service clamps this to 1..100
     */
    suspend fun listParties(page: Int, size: Int): PartyDirectoryPage
}

/**
 * A page of [PartySummary] plus the register-wide total, which is what lets the detector stop
 * paging without relying on an empty page (an offset scan can legitimately return a short page).
 */
data class PartyDirectoryPage(val items: List<PartySummary>, val total: Long)

/**
 * The three fields the reconciliation actually reads. Everything else party-service returns —
 * `legalName`, `email`, `tradingName` — is PII this control has no need for, so it is not mapped:
 * a field that is never bound cannot be logged by accident.
 *
 * @param status party lifecycle state as party-service spells it (`PENDING_KYC`, `ACTIVE`,
 *   `SUSPENDED`, `CLOSED`, `MERGED`). Kept as a String rather than a kyc-side enum on purpose —
 *   party-service owns this vocabulary and has already added a value (`MERGED`, ADR-0179) that its
 *   own OpenAPI enum still omits. An unknown value must degrade to "not one of the excluded ones",
 *   which a String does and a `valueOf` does not.
 */
data class PartySummary(val id: UUID, val status: String, val createdAt: Instant)
