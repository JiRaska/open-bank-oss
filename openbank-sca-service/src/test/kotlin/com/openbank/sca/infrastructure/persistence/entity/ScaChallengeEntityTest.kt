// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.persistence.entity

import com.openbank.sca.domain.model.DynamicLinkingData
import com.openbank.sca.domain.model.ScaChallenge
import com.openbank.sca.domain.model.ScaMethod
import com.openbank.sca.domain.model.ScaPurpose
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

class ScaChallengeEntityTest {

    private val now = OffsetDateTime.now()

    // ADR-0169 D2: documentSha256/ceremonyId must survive a DB round-trip like every other
    // dynamic-linking field — otherwise a reloaded document-signing challenge silently loses the
    // data consume() needs to authorise the operation (fromDomain/toDomain are the only place that
    // can drop them, since neither field was ever wired into the entity before this change).
    @Test
    fun `document-signing dynamic linking data survives a fromDomain-toDomain round trip`() {
        val challenge = ScaChallenge(
            partyId = UUID.randomUUID(),
            purpose = ScaPurpose.DOCUMENT_SIGNING,
            method = ScaMethod.BIOMETRIC,
            expiresAt = now.plusMinutes(5),
            dynamicLinkingData = DynamicLinkingData(
                amount = null,
                currency = null,
                creditorIban = null,
                creditorName = null,
                reference = null,
                documentSha256 = "abc123",
                ceremonyId = "ceremony-1",
            ),
            createdAt = now,
        )

        val restored = ScaChallengeEntity.fromDomain(challenge).toDomain()

        assertThat(restored.dynamicLinkingData).isEqualTo(challenge.dynamicLinkingData)
    }

    // Same reasoning for the card binding: a CARD_MANAGEMENT challenge reloaded from the DB
    // without cardId/cardAction would have nothing left for consume() to match, and the mapper is
    // the only place they can be dropped.
    @Test
    fun `card-management dynamic linking data survives a fromDomain-toDomain round trip`() {
        val challenge = ScaChallenge(
            partyId = UUID.randomUUID(),
            purpose = ScaPurpose.CARD_MANAGEMENT,
            method = ScaMethod.BIOMETRIC,
            expiresAt = now.plusMinutes(5),
            dynamicLinkingData = DynamicLinkingData(
                amount = null,
                currency = null,
                creditorIban = null,
                creditorName = null,
                reference = null,
                cardId = "card-1",
                cardAction = "LIMIT_INCREASE",
            ),
            createdAt = now,
        )

        val restored = ScaChallengeEntity.fromDomain(challenge).toDomain()

        assertThat(restored.dynamicLinkingData).isEqualTo(challenge.dynamicLinkingData)
    }

    @Test
    fun `challenge with no dynamic linking data round-trips to null`() {
        val challenge = ScaChallenge(
            partyId = UUID.randomUUID(),
            purpose = ScaPurpose.LOGIN,
            method = ScaMethod.PUSH_NOTIFICATION,
            expiresAt = now.plusMinutes(5),
            dynamicLinkingData = null,
            createdAt = now,
        )

        val restored = ScaChallengeEntity.fromDomain(challenge).toDomain()

        assertThat(restored.dynamicLinkingData).isNull()
    }
}
