// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.rest

import com.openbank.notification.domain.model.DeviceRegistration
import com.openbank.notification.domain.model.PushPlatform
import com.openbank.notification.infrastructure.persistence.entity.DeviceTokenEntity
import com.openbank.notification.infrastructure.persistence.repository.DeviceTokenRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * `POST /api/v1/devices` and `GET /api/v1/devices` — the validation branches, and the one
 * property the response body must hold: the provider token never leaves the registry.
 */
class DeviceResourceRegisterTest {

    private val repo = mockk<DeviceTokenRepository>()
    private val resource = DeviceResource().also { it.repo = repo }

    private val partyId: UUID = UUID.randomUUID()

    private fun entity(token: String = "provider-token-abc") = DeviceTokenEntity().also {
        it.deviceId = UUID.randomUUID()
        it.partyId = partyId
        it.appInstance = "inst-1"
        it.platform = "FCM"
        it.token = token
        it.status = "ACTIVE"
        it.registeredAt = Instant.parse("2026-09-01T00:00:00Z")
        it.createdAt = Instant.parse("2026-09-01T00:00:00Z")
        it.updatedAt = Instant.parse("2026-09-01T00:00:00Z")
    }

    @Suppress("UNCHECKED_CAST")
    private fun body(response: Response): Map<String, Any?> = response.entity as Map<String, Any?>

    @Test
    fun `register - missing partyId - 400 and nothing is persisted`(): Unit = runBlocking {
        val response = resource.register(
            DeviceResource.RegisterDeviceRequest(partyId = null, platform = "FCM", token = "t", appInstance = "i"),
        )

        assertThat(response.status).isEqualTo(Response.Status.BAD_REQUEST.statusCode)
        assertThat(body(response)["message"]).isEqualTo("partyId is required")
        coVerify(exactly = 0) { repo.register(any()) }
    }

    @Test
    fun `register - blank token - 400 and nothing is persisted`(): Unit = runBlocking {
        val response = resource.register(
            DeviceResource.RegisterDeviceRequest(partyId = partyId, platform = "FCM", token = "", appInstance = "i"),
        )

        assertThat(response.status).isEqualTo(Response.Status.BAD_REQUEST.statusCode)
        assertThat(body(response)["message"]).isEqualTo("token is required")
        coVerify(exactly = 0) { repo.register(any()) }
    }

    @Test
    fun `register - blank appInstance - 400 and nothing is persisted`(): Unit = runBlocking {
        val response = resource.register(
            DeviceResource.RegisterDeviceRequest(partyId = partyId, platform = "FCM", token = "t", appInstance = " "),
        )

        assertThat(response.status).isEqualTo(Response.Status.BAD_REQUEST.statusCode)
        assertThat(body(response)["message"]).isEqualTo("appInstance is required")
        coVerify(exactly = 0) { repo.register(any()) }
    }

    @Test
    fun `register - unknown platform - 400 naming the accepted values, not a 500`(): Unit = runBlocking {
        // An unparseable enum must not escape as IllegalArgumentException from valueOf.
        val response = resource.register(
            DeviceResource.RegisterDeviceRequest(
                partyId = partyId,
                platform = "WEBPUSH",
                token = "t",
                appInstance = "i",
            ),
        )

        assertThat(response.status).isEqualTo(Response.Status.BAD_REQUEST.statusCode)
        assertThat(body(response)["message"] as String).contains("FCM").contains("APNS")
        coVerify(exactly = 0) { repo.register(any()) }
    }

    @Test
    fun `register - platform is case-insensitive and the registration carries the parsed enum`(): Unit = runBlocking {
        val captured = slot<DeviceRegistration>()
        coEvery { repo.register(capture(captured)) } returns entity()

        val response = resource.register(
            DeviceResource.RegisterDeviceRequest(
                partyId = partyId,
                platform = "apns",
                token = "tok-1",
                appInstance = "inst-1",
                appVersion = "1.2.3",
                osVersion = "iOS 26",
            ),
        )

        assertThat(response.status).isEqualTo(Response.Status.CREATED.statusCode)
        assertThat(captured.captured.platform).isEqualTo(PushPlatform.APNS)
        assertThat(captured.captured.partyId).isEqualTo(partyId)
        assertThat(captured.captured.token).isEqualTo("tok-1")
        assertThat(captured.captured.appVersion).isEqualTo("1.2.3")
        assertThat(captured.captured.osVersion).isEqualTo("iOS 26")
    }

    @Test
    fun `register - the created view never echoes the provider token back`(): Unit = runBlocking {
        coEvery { repo.register(any()) } returns entity(token = "SECRET-PROVIDER-TOKEN")

        val response = resource.register(
            DeviceResource.RegisterDeviceRequest(
                partyId = partyId,
                platform = "FCM",
                token = "SECRET-PROVIDER-TOKEN",
                appInstance = "inst-1",
            ),
        )

        val view = body(response)
        assertThat(view).doesNotContainKey("token")
        assertThat(view.values.filterNotNull().map { it.toString() }).noneMatch { it.contains("SECRET-PROVIDER-TOKEN") }
        assertThat(view["status"]).isEqualTo("ACTIVE")
    }

    @Test
    fun `list - missing partyId - 400 and the registry is never queried`(): Unit = runBlocking {
        val response = resource.list(null)

        assertThat(response.status).isEqualTo(Response.Status.BAD_REQUEST.statusCode)
        coVerify(exactly = 0) { repo.listByParty(any()) }
    }

    @Test
    fun `list - returns the party's devices with a total and no tokens`(): Unit = runBlocking {
        coEvery { repo.listByParty(partyId) } returns listOf(entity("t1"), entity("t2"))

        val response = resource.list(partyId)

        val view = body(response)
        assertThat(view["total"]).isEqualTo(2)

        @Suppress("UNCHECKED_CAST")
        val items = view["items"] as List<Map<String, Any?>>
        assertThat(items).hasSize(2)
        assertThat(items).allSatisfy { assertThat(it).doesNotContainKey("token") }
    }
}
