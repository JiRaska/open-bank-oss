// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.rest

import com.openbank.notification.infrastructure.persistence.repository.DeviceTokenRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class DeviceResourceListTest {
    private val repo = mockk<DeviceTokenRepository>()
    private val resource = DeviceResource().also { it.repo = repo }

    @Test
    fun `bounded listing returns full count and fetches only requested page`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { repo.listByParty(partyId, 21) } returns emptyList()
        coEvery { repo.countByParty(partyId) } returns 42

        val response = resource.list(partyId, 21)

        assertEquals(200, response.status)
        assertEquals(mapOf("items" to emptyList<Any>(), "total" to 42L), response.entity)
        coVerify(exactly = 1) { repo.listByParty(partyId, 21) }
        coVerify(exactly = 1) { repo.countByParty(partyId) }
    }

    @Test
    fun `legacy listing keeps its full result count without an extra query`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { repo.listByParty(partyId, null) } returns emptyList()

        val response = resource.list(partyId, null)

        assertEquals(mapOf("items" to emptyList<Any>(), "total" to 0L), response.entity)
        coVerify(exactly = 0) { repo.countByParty(any()) }
    }

    @Test
    fun `invalid limit is rejected before a database read`(): Unit = runBlocking {
        val response = resource.list(UUID.randomUUID(), 201)
        assertEquals(400, response.status)
        coVerify(exactly = 0) { repo.listByParty(any(), any()) }
    }
}
