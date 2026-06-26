// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.account.domain.model

import com.openbank.libs.domain.money.CurrencyCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PocketRouterTest {

    private val accountId = UUID.randomUUID()

    private fun pocket(ccy: CurrencyCode, primary: Boolean = false, status: PocketStatus = PocketStatus.ACTIVE) =
        CurrencyPocket(
            id = UUID.randomUUID(),
            accountId = accountId,
            currency = ccy,
            isPrimary = primary,
            status = status,
            openedAt = Instant.now(),
            closedAt = null,
            version = 0L,
        )

    @Test
    fun `routes to the matching operable pocket`() {
        val pockets = listOf(pocket(CurrencyCode.CZK, primary = true), pocket(CurrencyCode.EUR))

        val result = PocketRouter.resolve(pockets, CurrencyCode.EUR, CurrencyCode.CZK, MissingPocketPolicy.REJECT)

        assertThat(result).isInstanceOf(PocketResolution.UseExisting::class.java)
        assertThat((result as PocketResolution.UseExisting).pocket.currency).isEqualTo(CurrencyCode.EUR)
    }

    @Test
    fun `rejects a payment to a frozen matching pocket`() {
        val pockets =
            listOf(pocket(CurrencyCode.CZK, primary = true), pocket(CurrencyCode.EUR, status = PocketStatus.FROZEN))

        val result = PocketRouter.resolve(pockets, CurrencyCode.EUR, CurrencyCode.CZK, MissingPocketPolicy.AUTO_CREATE)

        assertThat(result).isInstanceOf(PocketResolution.Rejected::class.java)
        assertThat((result as PocketResolution.Rejected).reason).contains("not operable")
    }

    @Test
    fun `REJECT policy rejects a missing pocket`() {
        val pockets = listOf(pocket(CurrencyCode.CZK, primary = true))

        val result = PocketRouter.resolve(pockets, CurrencyCode.USD, CurrencyCode.CZK, MissingPocketPolicy.REJECT)

        assertThat(result).isInstanceOf(PocketResolution.Rejected::class.java)
    }

    @Test
    fun `AUTO_CREATE policy signals creation of a new pocket`() {
        val pockets = listOf(pocket(CurrencyCode.CZK, primary = true))

        val result = PocketRouter.resolve(pockets, CurrencyCode.USD, CurrencyCode.CZK, MissingPocketPolicy.AUTO_CREATE)

        assertThat(result).isInstanceOf(PocketResolution.CreateNew::class.java)
        assertThat((result as PocketResolution.CreateNew).currency).isEqualTo(CurrencyCode.USD)
    }

    @Test
    fun `CONVERT_TO_PRIMARY routes a missing pocket to the primary`() {
        val pockets = listOf(pocket(CurrencyCode.CZK, primary = true))

        val result = PocketRouter.resolve(
            pockets,
            CurrencyCode.USD,
            CurrencyCode.CZK,
            MissingPocketPolicy.CONVERT_TO_PRIMARY,
        )

        assertThat(result).isInstanceOf(PocketResolution.ConvertToPrimary::class.java)
        val conv = result as PocketResolution.ConvertToPrimary
        assertThat(conv.from).isEqualTo(CurrencyCode.USD)
        assertThat(conv.primary.currency).isEqualTo(CurrencyCode.CZK)
    }

    @Test
    fun `CONVERT_TO_PRIMARY rejects when the primary pocket is absent`() {
        val pockets = listOf(pocket(CurrencyCode.EUR))

        val result = PocketRouter.resolve(
            pockets,
            CurrencyCode.USD,
            CurrencyCode.CZK,
            MissingPocketPolicy.CONVERT_TO_PRIMARY,
        )

        assertThat(result).isInstanceOf(PocketResolution.Rejected::class.java)
        assertThat((result as PocketResolution.Rejected).reason).contains("Primary pocket")
    }
}
