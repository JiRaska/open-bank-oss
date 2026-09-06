// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.rest

import com.openbank.notification.infrastructure.persistence.entity.NotificationPreferenceEntity
import com.openbank.notification.infrastructure.persistence.repository.NotificationPreferenceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The "a missing row means all-on" contract (#2). It is a real behaviour, not a null-check: the
 * app never seeds defaults, so a party who has never touched preferences must read as opted IN to
 * every togglable category — and a stored row must be echoed verbatim, including a `false`.
 */
class NotificationPreferenceResourceTest {

    private val repo = mockk<NotificationPreferenceRepository>()
    private val resource = NotificationPreferenceResource().also { it.repo = repo }

    private val partyId: UUID = UUID.randomUUID()

    private fun row(payments: Boolean, product: Boolean, marketing: Boolean) =
        NotificationPreferenceEntity().also {
            it.partyId = partyId
            it.paymentsPush = payments
            it.productPush = product
            it.marketingPush = marketing
            it.updatedAt = Instant.parse("2026-09-01T00:00:00Z")
        }

    @Test
    fun `get - no stored row - every category reads as on`(): Unit = runBlocking {
        coEvery { repo.getByParty(partyId) } returns null

        val dto = resource.get(partyId)

        assertThat(dto).isEqualTo(NotificationPreferenceDto(true, true, true))
    }

    @Test
    fun `get - a stored opt-out is returned, not defaulted back on`(): Unit = runBlocking {
        coEvery { repo.getByParty(partyId) } returns row(payments = true, product = false, marketing = false)

        val dto = resource.get(partyId)

        assertThat(dto.paymentsPush).isTrue()
        assertThat(dto.productPush).isFalse()
        assertThat(dto.marketingPush).isFalse()
    }

    @Test
    fun `set - forwards each flag positionally and echoes the persisted row`(): Unit = runBlocking {
        // A positional mix-up between the three booleans is invisible to an all-true payload,
        // so each flag differs from at least one other here.
        coEvery {
            repo.upsert(partyId, false, true, false)
        } returns row(payments = false, product = true, marketing = false)

        val dto = resource.set(partyId, NotificationPreferenceDto(paymentsPush = false, marketingPush = false))

        coVerify(exactly = 1) { repo.upsert(partyId, false, true, false) }
        assertThat(dto).isEqualTo(NotificationPreferenceDto(false, true, false))
    }

    @Test
    fun `set - the response mirrors the STORED row, not the request`(): Unit = runBlocking {
        // Whatever the store settled on is the truth the customer app must render.
        coEvery { repo.upsert(partyId, true, true, true) } returns row(true, true, false)

        val dto = resource.set(partyId, NotificationPreferenceDto(true, true, true))

        assertThat(dto.marketingPush).isFalse()
    }
}
