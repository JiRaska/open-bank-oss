// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ManagedNotificationTemplateTest {
    private fun copy(body: String, subject: String = "Transaction completed") = ManagedNotificationTemplate(
        id = UUID.randomUUID(),
        template = NotificationTemplate.TRANSACTION_COMPLETED,
        language = NotificationLanguage.EN,
        channel = NotificationChannel.EMAIL,
        revision = 1,
        subject = subject,
        body = body,
        state = ManagedTemplateState.DRAFT,
        createdBy = "maker",
        createdAt = Instant.now(),
    )

    @Test
    fun `renders only declared placeholders as escaped text`() {
        val rendered = copy("Amount {{amount}} {{currency}} received").render(
            mapOf("amount" to "<script>", "currency" to "EUR"),
        )
        assertThat(rendered.first).isEqualTo("Transaction completed")
        assertThat(rendered.second).isEqualTo("<p>Amount &lt;script&gt; EUR received</p>")
    }

    @Test
    fun `rejects missing variable and unreviewed link or markup`() {
        assertThatThrownBy { copy("Amount {{amount}} received") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { copy("<b>{{amount}} {{currency}}</b>") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { copy("Go to https://example.com/{{amount}}/{{currency}}") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { copy("Amount {{amount}} {{currency}}", "Hi {{amount}}") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
