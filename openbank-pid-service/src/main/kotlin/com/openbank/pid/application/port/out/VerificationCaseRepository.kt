// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.application.port.out

import com.openbank.pid.domain.model.VerificationCase
import com.openbank.pid.domain.model.VerificationCaseStatus
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Outbound persistence port for four-eyes identity-verification cases (ADR-0072 §1). */
interface VerificationCaseRepository {
    suspend fun findById(id: UUID): VerificationCase?

    /** The single ACTIVE (status != DECIDED) case for a dedup key, if any (uq_ivc_active_dedup). */
    suspend fun findActiveByDedupKey(dedupKey: String): VerificationCase?

    /** The most recently DECIDED case for a dedup key — the adjudication cache consulted by /resolve. */
    suspend fun findLatestDecidedByDedupKey(dedupKey: String): VerificationCase?

    /** Cockpit queue, newest-first. */
    suspend fun listByStatuses(statuses: List<VerificationCaseStatus>): List<VerificationCase>

    suspend fun save(case: VerificationCase): VerificationCase

    suspend fun update(case: VerificationCase): VerificationCase

    /**
     * GDPR Art. 17 (#15755): anonymise the applicant snapshot, blind index, dedup key and notes of
     * every not-yet-erased case the party was LINKED to (`first_link_party_id` / `final_link_party_id`)
     * — those applicant attributes are the erased person's own. Idempotent.
     *
     * @return the number of cases anonymised by this call.
     */
    suspend fun anonymizeLinkedTo(partyId: UUID, erasedAt: Instant): Int

    /**
     * GDPR Art. 17 (#15755): the cases that are the erased person's own but carry no link to them —
     * open and awaiting cases, rejected ones and DISTINCT_NEW decisions, found by the keys the
     * resolver built them from. Must be called while [ApplicantCaseKeys] can still be read from
     * the party, i.e. before its external ids are deleted. Idempotent (`erased_at is null`).
     *
     * The rules, and why each is safe (a case describing a different person must survive):
     * - **RČ blind index** (`RN:` cases): `blind_index` equal to the party's BIRTH_NUMBER index is
     *   the erased person's national identifier. Anonymised in full unless the case was DECIDED
     *   DISTINCT_NEW — that decision says the applicant is someone else sharing the identifier, so
     *   only the identifier (`blind_index`, `dedup_key`) is removed and the applicant is kept.
     * - **Name key** (`NK:` / `PK:` cases): the key is shared with every namesake, so a case is
     *   anonymised only when the erased party is NOT one of its candidates (a candidate is the person
     *   the applicant was compared against, not the applicant) and EVERY other live party holding the
     *   same key is a candidate — otherwise a live namesake could be the applicant and the case is left.
     *
     * @return the number of cases changed by this call.
     */
    suspend fun anonymizeApplicantCases(keys: ApplicantCaseKeys, erasedAt: Instant): Int
}

/**
 * What identifies an erased party's own verification cases, read from the party before its data is
 * anonymised. [birthNumberBlindIndex] is its BIRTH_NUMBER external id; [matchKey] is
 * `MatchKey.of(family, given, birthdate, birthplace)` over its stored attributes (the `NK:`/`PK:`
 * dedup-key body), with [birthdate] for the namesake lookup.
 */
data class ApplicantCaseKeys(
    val partyId: UUID,
    val birthNumberBlindIndex: String?,
    val matchKey: String,
    val birthdate: LocalDate,
)
