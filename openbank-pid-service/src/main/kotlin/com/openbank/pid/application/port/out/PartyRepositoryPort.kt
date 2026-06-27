// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.application.port.out

import com.openbank.libs.domain.event.DomainEvent
import com.openbank.pid.application.port.`in`.PartySearchQuery
import com.openbank.pid.domain.model.ExternalIdType
import com.openbank.pid.domain.model.Party
import com.openbank.pid.domain.model.PartyRelationship
import java.util.UUID

/** Outbound persistence port for the party aggregate. */
interface PartyRepository {

    suspend fun findById(id: UUID): Party?

    suspend fun findByExternalId(type: ExternalIdType, value: String): Party?

    suspend fun search(query: PartySearchQuery): List<Party>

    /**
     * Tier-2 identity resolution (ADR-0072 §3): return all parties whose normalized
     * `(familyName | givenName | birthdate | birthplace)` match key equals [matchKey].
     *
     * Implementations should use the existing `(family_name, birthdate)` index for an
     * efficient coarse filter and then refine in memory via [MatchKey.of].
     */
    suspend fun findCandidatesByMatchKey(matchKey: String): List<Party>

    /**
     * Tier-2′ blocking (ADR-0072): a coarse candidate set for probabilistic (Fellegi-Sunter)
     * scoring — every party born in [birthYear] whose family name starts with [familyInitial]
     * (case-insensitive). Deliberately loose so fuzzy duplicates (given-name typos, diacritics,
     * day-of-birth slips) survive into the scorer; precision is the scorer's job, not the query's.
     */
    suspend fun findCandidatesForProbabilistic(familyInitial: String, birthYear: Int): List<Party>

    suspend fun save(party: Party): Party

    suspend fun update(party: Party): Party

    suspend fun existsByExternalId(type: ExternalIdType, value: String): Boolean
}

/** Outbound persistence port for party relationships. */
interface PartyRelationshipRepository {

    suspend fun findById(id: UUID): PartyRelationship?

    suspend fun findByPartyId(partyId: UUID): List<PartyRelationship>

    suspend fun save(relationship: PartyRelationship): PartyRelationship

    suspend fun update(relationship: PartyRelationship): PartyRelationship
}

/** Outbound port that publishes party domain events to the transport (Kafka). */
interface PartyEventPublisher {

    suspend fun publish(event: DomainEvent)
}
