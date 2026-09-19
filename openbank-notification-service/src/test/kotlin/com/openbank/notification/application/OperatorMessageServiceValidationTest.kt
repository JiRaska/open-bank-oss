// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.application

import com.openbank.notification.domain.model.OperatorMessageTemplate
import com.openbank.notification.infrastructure.persistence.repository.NotificationRepository
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.mailer.reactive.ReactiveMailer
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `opsmessage.compose`'s request validation (issue #1384 / #1381) — everything that must be
 * refused *before* a row is persisted or a mail handed to the mailer.
 *
 * Both collaborators are strict mocks with nothing stubbed, so a request that slipped past
 * validation would fail on an unstubbed call rather than pass: the rejection is what keeps the
 * test green, which is the only way this can assert "nothing was sent".
 */
class OperatorMessageServiceValidationTest {

    private val mailer = mockk<ReactiveMailer>()
    private val repo = mockk<NotificationRepository>()

    private val service = OperatorMessageService().also {
        it.mailer = mailer
        it.notificationRepo = repo
    }

    private fun request(
        recipient: String = "ada@example.com",
        template: OperatorMessageTemplate = OperatorMessageTemplate.SUPPORT_FOLLOWUP,
        variables: Map<String, String> = mapOf("ticketReference" to "T-1"),
    ) = OperatorMessageRequest(UUID.randomUUID(), template, recipient, variables)

    private fun assertRejected(request: OperatorMessageRequest): Throwable {
        val thrown = catchThrowable { runBlocking { service.compose(request) } }
        assertThat(thrown).isInstanceOf(OperatorMessageRejected::class.java)
        return thrown
    }

    @Test
    fun `a blank recipient is refused`() {
        assertRejected(request(recipient = "   "))
        verify { mailer wasNot Called }
    }

    @Test
    fun `a recipient that is not an address is refused`() {
        assertRejected(request(recipient = "not-an-address"))
        assertRejected(request(recipient = "missing@domain"))
        assertRejected(request(recipient = "@example.com"))
        verify { mailer wasNot Called }
    }

    @Test
    fun `a CRLF header-injection payload cannot ride the recipient field`() {
        // The `$` anchor plus Regex.matches (whole-string) is what stops a trailing header.
        val injected = "ada@example.com\r\nBcc: attacker@evil.example"

        val error = assertRejected(request(recipient = injected))

        assertThat(error).hasMessageContaining("well-formed email address")
        verify { mailer wasNot Called }
    }

    @Test
    fun `an undeclared variable is refused and the message names it`() {
        val error = assertRejected(
            request(variables = mapOf("ticketReference" to "T-1", "otp" to "314159")),
        )

        assertThat(error).hasMessageContaining("undeclared").hasMessageContaining("otp")
        verify { mailer wasNot Called }
    }

    @Test
    fun `a MISSING declared variable is refused rather than rendered as an empty string`() {
        // #1381: the old asymmetric check only caught extra keys, so a missing one was rendered
        // blank and mailed to a real customer as an ordinary SENT row.
        val error = assertRejected(
            request(template = OperatorMessageTemplate.GENERIC_NOTICE, variables = mapOf("subject" to "Hi")),
        )

        assertThat(error).hasMessageContaining("missing").hasMessageContaining("note")
        verify { mailer wasNot Called }
    }

    @Test
    fun `an empty variable map is refused for a template that declares variables`() {
        assertRejected(request(variables = emptyMap()))
        verify { mailer wasNot Called }
    }

    @Test
    fun `the rejection message reports both halves when a key is swapped`() {
        val error = assertRejected(
            request(
                template = OperatorMessageTemplate.GENERIC_NOTICE,
                variables = mapOf("subject" to "Hi", "notes" to "typo"),
            ),
        )

        assertThat(error).hasMessageContaining("undeclared: [notes]").hasMessageContaining("missing: [note]")
    }
}
