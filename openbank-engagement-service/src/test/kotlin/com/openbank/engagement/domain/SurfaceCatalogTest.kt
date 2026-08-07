// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.domain

import com.openbank.engagement.domain.model.SurfaceCatalog
import com.openbank.engagement.domain.model.SurfaceContent
import com.openbank.engagement.domain.model.SurfaceContentType
import com.openbank.engagement.domain.model.SurfaceSlot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SurfaceCatalogTest {

    @Test
    fun `an OFFER entry cannot be constructed until ADR-0142 exists`() {
        assertThatThrownBy {
            SurfaceContent(
                id = "FAKE_PRE_APPROVED",
                slot = SurfaceSlot.HOME_BANNER,
                type = SurfaceContentType.OFFER,
                variables = emptySet(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("ADR-0142")
    }

    @Test
    fun `forSlot returns only entries for that slot`() {
        assertThat(SurfaceCatalog.forSlot(SurfaceSlot.HOME_BANNER)).isNotEmpty
        assertThat(SurfaceCatalog.forSlot(SurfaceSlot.REWARDS_HUB)).isEmpty()
    }

    @Test
    fun `unknownVariables rejects a variable the entry did not declare`() {
        assertThat(SurfaceCatalog.unknownVariables("SAVINGS_RATE_BANNER", mapOf("rateHeadline" to "4%")))
            .isEmpty()
        assertThat(SurfaceCatalog.unknownVariables("SAVINGS_RATE_BANNER", mapOf("bodyCopy" to "x")))
            .containsExactly("bodyCopy")
    }

    @Test
    fun `an unknown content id declares no variables and matches nothing`() {
        assertThat(SurfaceCatalog.exists("NOT_IN_THE_CATALOGUE")).isFalse
        assertThat(SurfaceCatalog.unknownVariables("NOT_IN_THE_CATALOGUE", mapOf("x" to "y")))
            .containsExactly("x")
    }
}
