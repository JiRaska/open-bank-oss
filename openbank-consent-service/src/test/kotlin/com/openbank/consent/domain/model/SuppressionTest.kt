// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/**
 * ADR-0219 D3's domain rules, pinned: the ALL/SCOPE/TOPIC shape is validated by construction
 * (an ALL entry with a value would read as scoped while covering everything), covers() is the
 * gate's exact matching semantics, and revoke is a one-way transition.
 */
class SuppressionTest {

    private fun entry(scope: SuppressionScope, value: String? = null, revokedAt: OffsetDateTime? = null) = Suppression(
        id = UUID.randomUUID(),
        partyId = UUID.randomUUID(),
        scope = scope,
        value = value,
        reason = SuppressionReason.CUSTOMER_OPTOUT,
        source = "preference-centre",
        createdBy = "operator",
        createdAt = OffsetDateTime.now(),
        revokedAt = revokedAt,
        revokedBy = null,
    )

    @Test
    fun `ALL takes no value, SCOPE and TOPIC require one`() {
        assertThatThrownBy { entry(SuppressionScope.ALL, "loans") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { entry(SuppressionScope.SCOPE, null) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { entry(SuppressionScope.TOPIC, "") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(entry(SuppressionScope.ALL)).isNotNull()
        assertThat(entry(SuppressionScope.SCOPE, "MARKETING_COMMS_EMAIL")).isNotNull()
        assertThat(entry(SuppressionScope.TOPIC, "loans")).isNotNull()
    }

    @Test
    fun `covers — ALL suppresses everything, SCOPE and TOPIC only their own`() {
        assertThat(entry(SuppressionScope.ALL).covers("MARKETING_COMMS_EMAIL", "anything")).isTrue()

        val scoped = entry(SuppressionScope.SCOPE, "MARKETING_COMMS_EMAIL")
        assertThat(scoped.covers("MARKETING_COMMS_EMAIL", null)).isTrue()
        assertThat(scoped.covers("MARKETING_COMMS_INAPP", null)).isFalse()

        val topical = entry(SuppressionScope.TOPIC, "loans")
        assertThat(topical.covers("MARKETING_COMMS_EMAIL", "loans")).isTrue()
        assertThat(topical.covers("MARKETING_COMMS_EMAIL", "savings")).isFalse()
        // A null topic can never be topic-suppressed — otherwise one entry would suppress everything.
        assertThat(topical.covers("MARKETING_COMMS_EMAIL", null)).isFalse()
    }

    @Test
    fun `revoke is one-way and records who and when`() {
        val active = entry(SuppressionScope.ALL)
        assertThat(active.active).isTrue()

        val revoked = active.revoke("rm-operator", OffsetDateTime.now())
        assertThat(revoked.active).isFalse()
        assertThat(revoked.revokedBy).isEqualTo("rm-operator")
        assertThat(revoked.revokedAt).isNotNull()

        assertThatThrownBy { revoked.revoke("again", OffsetDateTime.now()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
