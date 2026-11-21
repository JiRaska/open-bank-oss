// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.domain.model

import com.openbank.libs.domain.cards.scheme.CardScheme
import com.openbank.libs.domain.cards.scheme.NetworkTokenStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The terminal-state rules of the two card-side registers the pitest matrix flagged as
 * NO_COVERAGE (2026-11-18 mutation sweep): [CardDisputeCase.terminal] and
 * [CardTokenRegistration.terminal]. Both are expressed on the aggregate on purpose — a rule
 * that lived only in an adapter would be a rule the service cannot state about itself — so
 * both deserve an assertion against the aggregate itself, not only against an adapter that
 * happens to enforce them.
 */
class DisputeAndTokenTerminalRulesTest {

    @Test
    fun `a dispute case is terminal exactly when won lost or withdrawn`() {
        assertThat(caseWith(DisputeStatus.OPEN).terminal).isFalse()
        assertThat(caseWith(DisputeStatus.EVIDENCE_SUBMITTED).terminal).isFalse()
        assertThat(caseWith(DisputeStatus.WON).terminal).isTrue()
        assertThat(caseWith(DisputeStatus.LOST).terminal).isTrue()
        assertThat(caseWith(DisputeStatus.WITHDRAWN).terminal).isTrue()
    }

    @Test
    fun `a token registration is terminal exactly when the network says deleted`() {
        assertThat(tokenWith(NetworkTokenStatus.ACTIVE).terminal).isFalse()
        assertThat(tokenWith(NetworkTokenStatus.SUSPENDED).terminal).isFalse()
        assertThat(tokenWith(NetworkTokenStatus.DELETED).terminal).isTrue()
    }

    @Test
    fun `refusal and outcome values carry their reason for the caller to render`() {
        // Values, not exceptions: each refusal renders differently to an operator, so the
        // reason must survive the boundary intact.
        val disputeRefusal = DisputeOutcome.Refused(
            DisputeRefusal.AMOUNT_EXCEEDS_CLEARED,
            "cleared 1000, disputed 1200",
        )
        assertThat(disputeRefusal.reason).isEqualTo(DisputeRefusal.AMOUNT_EXCEEDS_CLEARED)
        assertThat(disputeRefusal.detail).contains("1200")

        val tokenRefusal = TokenOutcome.Refused(TokenRefusal.CARD_NOT_ACTIVE, "card blocked")
        assertThat(tokenRefusal.reason).isEqualTo(TokenRefusal.CARD_NOT_ACTIVE)
        assertThat(tokenRefusal.detail).isEqualTo("card blocked")

        val accepted = DisputeOutcome.Accepted(caseWith(DisputeStatus.OPEN))
        assertThat(accepted.case.status).isEqualTo(DisputeStatus.OPEN)
        val provisioned = TokenOutcome.Provisioned(tokenWith(NetworkTokenStatus.ACTIVE))
        assertThat(provisioned.registration.status).isEqualTo(NetworkTokenStatus.ACTIVE)
    }

    @Test
    fun `a degraded token list says so, a live one does not`() {
        val live =
            TokenRegistrations(tokens = listOf(tokenWith(NetworkTokenStatus.ACTIVE)), source = TokenReadSource.NETWORK)
        assertThat(live.degradedReason).isNull()

        val fallback = TokenRegistrations(
            tokens = emptyList(),
            source = TokenReadSource.LOCAL_MIRROR,
            degradedReason = "VTS timeout",
        )
        assertThat(fallback.degradedReason).isEqualTo("VTS timeout")
        // A degraded answer must not share a signal with a good one (#4348 family).
        assertThat(fallback.source).isNotEqualTo(live.source)
    }

    private fun caseWith(status: DisputeStatus) = CardDisputeCase(
        id = UUID.randomUUID(),
        authorizationId = UUID.randomUUID(),
        cardId = UUID.randomUUID(),
        networkCaseId = "NC-1",
        reasonCode = "4853",
        amountMinorUnits = 12990,
        currencyCode = "CZK",
        status = status,
        scheme = CardScheme.VISA,
        schemeStatus = "ACCEPTED_BY_NETWORK",
        respondByDate = LocalDate.of(2026, 12, 31),
        evidenceReference = null,
        openedAt = Instant.parse("2026-11-01T10:00:00Z"),
        updatedAt = Instant.parse("2026-11-01T10:00:00Z"),
    )

    private fun tokenWith(status: NetworkTokenStatus) = CardTokenRegistration(
        id = UUID.randomUUID(),
        cardId = UUID.randomUUID(),
        tokenReference = "tok-ref-1",
        requestorId = "wallet-apple-pay",
        requestorLabel = "Apple Pay",
        last4 = "1234",
        status = status,
        scheme = CardScheme.MASTERCARD,
        expiry = LocalDate.of(2029, 6, 30),
        provisionedAt = Instant.parse("2026-11-01T10:00:00Z"),
        updatedAt = Instant.parse("2026-11-01T10:00:00Z"),
    )
}
