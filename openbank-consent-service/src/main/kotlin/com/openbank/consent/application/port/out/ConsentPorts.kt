// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.consent.application.port.out

import com.openbank.consent.domain.event.ConsentExpired
import com.openbank.consent.domain.event.ConsentGranted
import com.openbank.consent.domain.event.ConsentRejected
import com.openbank.consent.domain.event.ConsentRevoked
import com.openbank.consent.domain.model.Consent
import java.util.UUID

/** Outbound persistence port for the consent aggregate. */
interface ConsentRepository {

    suspend fun save(consent: Consent): Consent

    suspend fun findById(id: UUID): Consent?

    suspend fun findByPartyId(partyId: UUID): List<Consent>

    suspend fun findByGranteeId(granteeId: String): List<Consent>

    suspend fun findActiveByGranteeAndParty(granteeId: String, partyId: UUID): List<Consent>
}

/** Outbound port that publishes consent domain events to the transport (Kafka). */
interface ConsentEventPublisher {

    suspend fun publish(event: ConsentGranted)

    suspend fun publish(event: ConsentRevoked)

    suspend fun publish(event: ConsentExpired)

    suspend fun publish(event: ConsentRejected)
}

/** Read-model snapshot of an SCA challenge as fetched from the sca-service. */
data class ScaChallengeSnapshot(
    val id: UUID,
    val partyId: UUID,
    val purpose: String,
    val status: String
)

/** Outbound port to the sca-service for resolving the state of an SCA challenge. */
interface ScaChallengeClient {

    suspend fun getChallenge(challengeId: UUID): ScaChallengeSnapshot
}
