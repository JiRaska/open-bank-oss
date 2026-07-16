// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.rest

import com.openbank.notification.application.NotificationConsumer
import com.openbank.notification.domain.model.NotificationTemplate
import com.openbank.notification.domain.model.OperatorMessagePurpose
import com.openbank.notification.infrastructure.persistence.entity.OperatorMessageEntity
import com.openbank.notification.infrastructure.persistence.repository.OperatorMessageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.security.Principal
import java.time.Instant
import java.util.UUID

class OperatorMessageResourceTest {

    private val repo = mockk<OperatorMessageRepository>()
    private val consumer = mockk<NotificationConsumer>()
    private val identity = mockk<SecurityIdentity> {
        every { principal } returns Principal { "operator-1" }
    }
    private val resource = OperatorMessageResource().also {
        it.repo = repo
        it.consumer = consumer
        it.identity = identity
    }

    private fun draftRequest(
        referenceId: String = "TICKET-123",
        purpose: OperatorMessagePurpose = OperatorMessagePurpose.SERVICE,
        template: NotificationTemplate = NotificationTemplate.OPERATOR_ACCOUNT_NOTICE,
    ) = DraftOperatorMessageRequest(
        partyId = UUID.randomUUID(),
        template = template,
        referenceId = referenceId,
        purpose = purpose,
    )

    // ADR-0176 D2: the whole point of the catalogue design is that referenceId is validated
    // BEFORE a row is ever written — never operator-supplied prose reaching four-eyes unchecked.
    @Test
    fun `draft rejects a referenceId that does not match the allow-listed pattern`(): Unit = runBlocking {
        val response = resource.draft(draftRequest(referenceId = "not valid! <script>"))

        assertEquals(422, response.status)
        coVerify(exactly = 0) { repo.create(any(), any(), any(), any(), any(), any()) }
    }

    // ADR-0176 D6: refused at the API, not merely hidden in the UI.
    @Test
    fun `draft refuses MARKETING purpose at the API`(): Unit = runBlocking {
        val response = resource.draft(draftRequest(purpose = OperatorMessagePurpose.MARKETING))

        assertEquals(422, response.status)
        coVerify(exactly = 0) { repo.create(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `draft rejects any template other than the one supported today`(): Unit = runBlocking {
        val response = resource.draft(draftRequest(template = NotificationTemplate.WELCOME))

        assertEquals(422, response.status)
        coVerify(exactly = 0) { repo.create(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `draft persists a valid request and returns 201`(): Unit = runBlocking {
        val request = draftRequest()
        val entity = operatorMessageEntity(partyId = request.partyId, referenceId = request.referenceId)
        coEvery {
            repo.create(any(), request.partyId, request.template, request.referenceId, request.purpose, "operator-1")
        } returns entity

        val response = resource.draft(request)

        assertEquals(201, response.status)
        coVerify(exactly = 1) {
            repo.create(any(), request.partyId, request.template, request.referenceId, request.purpose, "operator-1")
        }
    }

    @Test
    fun `submit refuses an already-resolved message`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        coEvery { repo.findByMessageId(id) } returns operatorMessageEntity(id = id, status = "SENT")

        val response = resource.submit(id)

        assertEquals(409, response.status)
        coVerify(exactly = 0) { repo.markSent(any()) }
    }

    @Test
    fun `submit dispatches and marks the message SENT`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        coEvery { repo.findByMessageId(id) } returns operatorMessageEntity(id = id, status = "PENDING_APPROVAL")
        every { consumer.dispatch(any()) } returns Uni.createFrom().voidItem()
        coEvery { repo.markSent(id) } returns Unit

        val response = resource.submit(id)

        assertEquals(Response.Status.OK.statusCode, response.status)
        coVerify(exactly = 1) { repo.markSent(id) }
    }

    private fun operatorMessageEntity(
        id: UUID = UUID.randomUUID(),
        partyId: UUID = UUID.randomUUID(),
        referenceId: String = "TICKET-123",
        status: String = "PENDING_APPROVAL",
    ) = OperatorMessageEntity().also {
        it.messageId = id
        it.partyId = partyId
        it.template = NotificationTemplate.OPERATOR_ACCOUNT_NOTICE.name
        it.referenceId = referenceId
        it.purpose = OperatorMessagePurpose.SERVICE.name
        it.status = status
        it.makerId = "operator-1"
        it.createdAt = Instant.now()
        it.updatedAt = Instant.now()
    }
}
