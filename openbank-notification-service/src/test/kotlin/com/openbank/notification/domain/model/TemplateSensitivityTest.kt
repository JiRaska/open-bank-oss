// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Executable form of the storage guarantee: a rendered authentication secret never
 * reaches `notifications.body`, where any ROLE_OPERATOR could read it and complete the
 * customer's SCA (ADR-0021). If someone renders a new secret into a body without
 * classifying the template, these tests fail.
 */
class TemplateSensitivityTest {

    @Test
    fun `secret-bearing templates are classified (allow-list, not block-list)`() {
        assertThat(TemplateSensitivity.isSecret(NotificationTemplate.OTP_CODE)).isTrue()
    }

    @Test
    fun `ordinary templates are not classified secret and store their rendered body`() {
        val ordinary = NotificationTemplate.entries - TemplateSensitivity.SECRET_TEMPLATES
        assertThat(ordinary).isNotEmpty()
        for (t in ordinary) {
            assertThat(TemplateSensitivity.isSecret(t)).isFalse()
            assertThat(TemplateSensitivity.bodyForStorage(t, "<p>rendered</p>")).isEqualTo("<p>rendered</p>")
        }
    }

    @Test
    fun `the rendered secret is never the stored body`() {
        val rendered = "<h2>Verification Code</h2><p>Your code is: <b>828913</b>. Valid for 5 minutes.</p>"
        for (t in TemplateSensitivity.SECRET_TEMPLATES) {
            val stored = TemplateSensitivity.bodyForStorage(t, rendered)
            assertThat(stored).isEqualTo(TemplateSensitivity.REDACTED_BODY)
            assertThat(stored).doesNotContain("828913")
            assertThat(stored).isNotEqualTo(rendered)
        }
    }

    @Test
    fun `the placeholder says why, so an operator reading it is not left guessing`() {
        assertThat(TemplateSensitivity.REDACTED_BODY).startsWith("[REDACTED]")
        assertThat(TemplateSensitivity.REDACTED_BODY).contains("ADR-0021")
        assertThat(TemplateSensitivity.REDACTED_BODY).contains("Art. 5(1)(c)")
    }

    @Test
    fun `the classification set is pinned, so widening or narrowing it is deliberate`() {
        // This pins the CURRENT set: it fails when someone edits SECRET_TEMPLATES, forcing that
        // edit through review. It does NOT — and cannot — catch a new enum constant whose render
        // embeds a secret but which nobody classified; the set is simply unchanged then. That gap
        // is covered by review of renderTemplate, and is why the classification lives next to the
        // enum rather than in the consumer.
        assertThat(TemplateSensitivity.SECRET_TEMPLATES).containsExactlyInAnyOrder(
            NotificationTemplate.OTP_CODE,
        )
    }
}
