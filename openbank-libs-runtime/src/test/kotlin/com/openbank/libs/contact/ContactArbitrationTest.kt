// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.contact

import com.openbank.libs.contact.ContactArbitration.Eligible
import com.openbank.libs.contact.ContactArbitration.Tier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * ADR-0219 D1's arbitration order, pinned: the winner of a contested slot is a reviewed decision,
 * so the full tier order and both tie-breaks are tested — an iteration-order accident here is a
 * compliance-relevant content choice nobody reviewed.
 */
class ContactArbitrationTest {

    private fun item(tier: Tier, priority: Int = 0, id: UUID = UUID.randomUUID()) =
        Eligible(tier, priority, id, payload = tier.name)

    @Test
    fun `empty input wins nothing`() {
        assertThat(ContactArbitration.chooseWinner<String>(emptyList())).isNull()
    }

    @Test
    fun `tier order — service exempt beats standing decision beats NBA beats campaign step`() {
        val shuffled = listOf(
            item(Tier.CAMPAIGN_STEP),
            item(Tier.NBA_RANKED),
            item(Tier.SERVICE_EXEMPT),
            item(Tier.STANDING_DECISION),
        )
        assertThat(ContactArbitration.chooseWinner(shuffled)?.tier).isEqualTo(Tier.SERVICE_EXEMPT)
        assertThat(ContactArbitration.chooseWinner(shuffled - Tier.SERVICE_EXEMPT)?.tier)
            .isEqualTo(Tier.STANDING_DECISION)
        assertThat(
            ContactArbitration.chooseWinner(shuffled - Tier.SERVICE_EXEMPT - Tier.STANDING_DECISION)?.tier,
        ).isEqualTo(Tier.NBA_RANKED)
    }

    @Test
    fun `inside a tier the higher priority wins`() {
        val low = item(Tier.NBA_RANKED, priority = 1)
        val high = item(Tier.NBA_RANKED, priority = 9)
        assertThat(ContactArbitration.chooseWinner(listOf(low, high))).isEqualTo(high)
    }

    @Test
    fun `final ties break by id — deterministically`() {
        val a = item(Tier.CAMPAIGN_STEP, priority = 5, id = UUID.fromString("00000000-0000-0000-0000-00000000000a"))
        val b = item(Tier.CAMPAIGN_STEP, priority = 5, id = UUID.fromString("00000000-0000-0000-0000-00000000000b"))
        assertThat(ContactArbitration.chooseWinner(listOf(b, a))).isEqualTo(a)
        assertThat(ContactArbitration.chooseWinner(listOf(a, b))).isEqualTo(a)
    }

    private operator fun <T> List<Eligible<T>>.minus(tier: Tier): List<Eligible<T>> = filterNot { it.tier == tier }
}
