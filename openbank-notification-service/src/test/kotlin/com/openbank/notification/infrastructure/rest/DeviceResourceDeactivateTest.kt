// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.rest

import com.openbank.notification.infrastructure.persistence.repository.DeviceTokenRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class DeviceResourceDeactivateTest {

    private val repo = mockk<DeviceTokenRepository>()
    private val resource = DeviceResource().also { it.repo = repo }

    @Test
    fun `deactivate without partyId - operator path - returns 204`(): Unit = runBlocking {
        val deviceId = UUID.randomUUID()
        coEvery { repo.deactivate(deviceId, null) } returns true

        val response = resource.deactivate(deviceId, partyId = null)

        assertEquals(Response.Status.NO_CONTENT.statusCode, response.status)
        coVerify(exactly = 1) { repo.deactivate(deviceId, null) }
    }

    @Test
    fun `deactivate with partyId - scopes update to that party's token only`(): Unit = runBlocking {
        // customer-edge injects partyId from JWT; operator/admin may also supply it to narrow scope
        val deviceId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        coEvery { repo.deactivate(deviceId, partyId) } returns true

        val response = resource.deactivate(deviceId, partyId = partyId)

        assertEquals(Response.Status.NO_CONTENT.statusCode, response.status)
        coVerify(exactly = 1) { repo.deactivate(deviceId, partyId) }
    }

    @Test
    fun `deactivate not found returns 404`(): Unit = runBlocking {
        val deviceId = UUID.randomUUID()
        coEvery { repo.deactivate(deviceId, null) } returns false

        val response = resource.deactivate(deviceId, partyId = null)

        assertEquals(Response.Status.NOT_FOUND.statusCode, response.status)
    }
}
