// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.rest

import com.openbank.notification.domain.model.NotificationTemplate
import com.openbank.notification.domain.model.OperatorMessageTemplate
import com.openbank.notification.domain.model.TemplateSensitivity
import com.openbank.notification.infrastructure.persistence.entity.NotificationEntity
import com.openbank.notification.infrastructure.persistence.repository.NotificationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The read-side half of the secret-template control (issue #1325 / #1386): whatever is in the
 * `body` column, a secret-bearing template must never be served to a `ROLE_OPERATOR` reader.
 *
 * The rows here deliberately carry a *rendered secret* in `body` — the state a pre-redaction row
 * is actually in — so the assertion fails if the read path stops redacting, which is exactly the
 * fail-open branch that #1386 found for `OperatorMessageTemplate` names.
 */
class NotificationResourceRedactionTest {

    private val repo = mockk<NotificationRepository>()
    private val resource = NotificationResource().also { it.repo = repo }

    private val id: UUID = UUID.randomUUID()
    private val partyId: UUID = UUID.randomUUID()

    private fun row(template: String, body: String) = NotificationEntity().also {
        it.notificationId = id
        it.partyId = partyId
        it.channel = "EMAIL"
        it.template = template
        it.recipient = "a@b.example"
        it.subject = "s"
        it.body = body
        it.status = "SENT"
        it.createdAt = Instant.parse("2026-09-01T00:00:00Z")
    }

    @Suppress("UNCHECKED_CAST")
    private fun body(response: Response): Map<String, Any?> = response.entity as Map<String, Any?>

    @Test
    fun `get - a secret-bearing template is redacted even when the stored row holds the code`(): Unit =
        runBlocking {
            coEvery { repo.findById(id) } returns row(NotificationTemplate.OTP_CODE.name, "Your code is 314159")

            val view = body(resource.getNotification(id))

            assertThat(view["body"]).isEqualTo(TemplateSensitivity.REDACTED_BODY)
            assertThat(view["body"] as String).doesNotContain("314159")
        }

    @Test
    fun `get - an ordinary template is served verbatim`(): Unit = runBlocking {
        coEvery { repo.findById(id) } returns row(NotificationTemplate.WELCOME.name, "<p>Hello Ada</p>")

        assertThat(body(resource.getNotification(id))["body"]).isEqualTo("<p>Hello Ada</p>")
    }

    @Test
    fun `get - an operator-message template is classified, not fail-open (issue 1386)`(): Unit = runBlocking {
        // GENERIC_NOTICE is non-secret today, so the body is served — but it must reach that
        // answer through OperatorMessageTemplateSensitivity, which is what makes a future
        // secret-bearing operator template redactable by classification alone.
        coEvery {
            repo.findById(id)
        } returns row(OperatorMessageTemplate.GENERIC_NOTICE.name, "<p>Scheduled maintenance</p>")

        assertThat(body(resource.getNotification(id))["body"]).isEqualTo("<p>Scheduled maintenance</p>")
    }

    @Test
    fun `get - a template matching neither enum fails open rather than 500-ing`(): Unit = runBlocking {
        coEvery { repo.findById(id) } returns row("TEMPLATE_REMOVED_IN_A_LATER_RELEASE", "legacy body")

        val response = resource.getNotification(id)

        assertThat(response.status).isEqualTo(Response.Status.OK.statusCode)
        assertThat(body(response)["body"]).isEqualTo("legacy body")
    }

    @Test
    fun `get - unknown id is 404`(): Unit = runBlocking {
        coEvery { repo.findById(id) } returns null

        assertThat(resource.getNotification(id).status).isEqualTo(Response.Status.NOT_FOUND.statusCode)
    }

    @Test
    fun `list - the summary view never carries a body at all`(): Unit = runBlocking {
        coEvery {
            repo.pageAll(0, 20)
        } returns Pair(listOf(row(NotificationTemplate.OTP_CODE.name, "Your code is 271828")), 1L)

        val view = body(resource.listNotifications(0, 20, null))

        @Suppress("UNCHECKED_CAST")
        val items = view["items"] as List<Map<String, Any?>>
        assertThat(items.single()).doesNotContainKey("body")
        assertThat(view["total"]).isEqualTo(1L)
    }

    @Test
    fun `list - page size is clamped into 1_100 before it reaches the repository`(): Unit = runBlocking {
        coEvery { repo.pageAll(0, 100) } returns Pair(emptyList(), 0L)
        coEvery { repo.pageAll(0, 1) } returns Pair(emptyList(), 0L)

        resource.listNotifications(0, 5_000, null)
        resource.listNotifications(0, 0, null)

        coVerify(exactly = 1) { repo.pageAll(0, 100) }
        coVerify(exactly = 1) { repo.pageAll(0, 1) }
    }

    @Test
    fun `list - a partyId routes to the party-scoped query, not the unscoped one`(): Unit = runBlocking {
        coEvery { repo.pageByParty(partyId, 2, 20) } returns Pair(emptyList(), 0L)

        resource.listNotifications(2, 20, partyId)

        coVerify(exactly = 1) { repo.pageByParty(partyId, 2, 20) }
        coVerify(exactly = 0) { repo.pageAll(any(), any()) }
    }

    @Test
    fun `markRead - missing partyId is 400 and the update is never attempted`(): Unit = runBlocking {
        val response = resource.markRead(id, null)

        assertThat(response.status).isEqualTo(Response.Status.BAD_REQUEST.statusCode)
        coVerify(exactly = 0) { repo.markRead(any(), any()) }
    }

    @Test
    fun `markRead - another party's row is a 404 with no existence oracle`(): Unit = runBlocking {
        coEvery { repo.markRead(id, partyId) } returns false

        val response = resource.markRead(id, partyId)

        assertThat(response.status).isEqualTo(Response.Status.NOT_FOUND.statusCode)
        assertThat(body(response)["message"]).isEqualTo("Notification not found")
    }

    @Test
    fun `markAllRead - requires a partyId and otherwise reports how many flipped`(): Unit = runBlocking {
        assertThat(resource.markAllRead(null).status).isEqualTo(Response.Status.BAD_REQUEST.statusCode)

        coEvery { repo.markAllRead(partyId) } returns 3

        assertThat(body(resource.markAllRead(partyId))["marked"]).isEqualTo(3)
    }
}
