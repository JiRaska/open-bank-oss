// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.rest

import com.openbank.notification.infrastructure.persistence.entity.NotificationEntity
import com.openbank.notification.infrastructure.persistence.repository.NotificationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Party-scoped fetch-on-tap read (ADR-0135 §3, issue #1182). Mirrors the IDOR-safe pattern of
 * [DeviceResourceDeactivateTest]: the repository SELECT is scoped by partyId, so a caller can only
 * read their OWN notification — an id owned by another party (or absent) is a 404 with no oracle.
 */
class NotificationResourceReadTest {

    private val repo = mockk<NotificationRepository>()
    private val resource = NotificationResource().also { it.repo = repo }

    private fun entity(id: UUID, partyId: UUID) = NotificationEntity().also {
        it.notificationId = id
        it.partyId = partyId
        it.channel = "PUSH"
        it.template = "TRANSACTION_COMPLETED"
        it.recipient = "customer@example.com"
        it.subject = "Transaction completed"
        it.body = "<p>Transaction of <b>10.00 EUR</b> completed successfully.</p>"
        it.status = "SENT"
        it.createdAt = Instant.now()
    }

    @Test
    fun `own notification - owner reads their row - returns 200`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        coEvery { repo.findByIdAndParty(id, partyId) } returns entity(id, partyId)

        val response = resource.getOwnNotification(id, partyId)

        assertEquals(Response.Status.OK.statusCode, response.status)
        @Suppress("UNCHECKED_CAST")
        val body = response.entity as Map<String, Any?>
        assertEquals(id, body["id"])
        assertEquals(partyId, body["partyId"])
        coVerify(exactly = 1) { repo.findByIdAndParty(id, partyId) }
    }

    @Test
    fun `own notification - not the caller's party - returns 404 (IDOR guard)`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val otherParty = UUID.randomUUID()
        // Repo returns null when the row is not owned by this party — the resource must 404.
        coEvery { repo.findByIdAndParty(id, otherParty) } returns null

        val response = resource.getOwnNotification(id, otherParty)

        assertEquals(Response.Status.NOT_FOUND.statusCode, response.status)
        coVerify(exactly = 1) { repo.findByIdAndParty(id, otherParty) }
    }

    @Test
    fun `own notification - missing partyId - returns 400`(): Unit = runBlocking {
        val response = resource.getOwnNotification(UUID.randomUUID(), partyId = null)

        assertEquals(Response.Status.BAD_REQUEST.statusCode, response.status)
    }
}
