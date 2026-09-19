// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

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
    fun `bounded list preserves full total and delegates limit to storage`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { repo.listByParty(partyId, 21) } returns emptyList()
        coEvery { repo.countByParty(partyId) } returns 42

        val response = resource.list(partyId, 21)

        assertEquals(200, response.status)
        assertEquals(42L, (response.entity as Map<*, *>)["total"])
        coVerify(exactly = 1) { repo.listByParty(partyId, 21) }
    }

    @Test
    fun `invalid limit rejects before storage read`(): Unit = runBlocking {
        val response = resource.list(UUID.randomUUID(), 101)

        assertEquals(400, response.status)
        coVerify(exactly = 0) { repo.listByParty(any(), any()) }
    }
}
