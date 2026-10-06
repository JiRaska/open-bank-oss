// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain

import com.openbank.treasury.domain.DealFixtures.NOW
import com.openbank.treasury.domain.DealFixtures.dealer
import com.openbank.treasury.domain.DealFixtures.placement
import com.openbank.treasury.domain.DealFixtures.withinLimit
import com.openbank.treasury.domain.model.LimitNote
import com.openbank.treasury.infrastructure.rest.DealResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LimitNoteTest {
    @Test
    fun `the note a submit writes parses back to the check's figures`() {
        val d = placement()
        val check = withinLimit(d)
        val submitted = d.submit(dealer, check, NOW, DealFixtures.withinProduct)

        val snapshot = LimitNote.parse(submitted.history.last().note)

        assertThat(snapshot).isNotNull
        assertThat(snapshot!!.limit).isEqualByComparingTo(check.limit)
        assertThat(snapshot.currency).isEqualTo(check.currency)
        assertThat(snapshot.exposureAfter).isEqualByComparingTo(check.exposureAfter)
        assertThat(snapshot.headroomAfter).isEqualByComparingTo(check.headroomAfter)
    }

    @Test
    fun `a note that is not a limit note has no snapshot`() {
        assertThat(LimitNote.parse("rate off-market")).isNull()
        assertThat(LimitNote.parse(null)).isNull()
        assertThat(LimitNote.parse("limit 1 CZK, exposure after x, headroom 2")).isNull()
    }

    @Test
    fun `the API carries the structured figures next to the unchanged note`() {
        val d = placement()
        val submitted = d.submit(dealer, withinLimit(d), NOW, DealFixtures.withinProduct)

        val last = DealResponse.from(submitted).history.last()

        assertThat(last.note).isEqualTo(submitted.history.last().note)
        assertThat(last.limitSnapshot).isNotNull
        assertThat(last.limitSnapshot!!.headroomAfter).isEqualByComparingTo(withinLimit(d).headroomAfter)
        assertThat(DealResponse.from(d).history.mapNotNull { it.limitSnapshot }).isEmpty()
    }
}
