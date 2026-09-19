// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain

import com.openbank.notification.domain.model.NotificationLanguage
import com.openbank.notification.domain.model.NotificationTemplate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ApprovalCopyTest {

    private val vars = mapOf(
        "entityName" to "Example s.r.o.",
        "kind" to "PAYMENT",
        "amountFormatted" to "1 000,00 CZK",
        "payeeName" to "Payee Example",
        "initiatorName" to "Initiator Example",
        "expiresAt" to "21. 9. 2026 10:15",
        "reason" to "Wrong account",
    )

    @Test
    fun `every approval template renders in both languages and the two differ`() {
        ApprovalCopy.TEMPLATES.forEach { template ->
            val scoped = vars.filterKeys { it in template.variables }
            val (csSubject, csBody) = ApprovalCopy.render(template, scoped, NotificationLanguage.CS)
            val (enSubject, enBody) = ApprovalCopy.render(template, scoped, NotificationLanguage.EN)
            assertThat(csSubject).describedAs("$template cs").isNotBlank().isNotEqualTo(enSubject)
            assertThat(csBody).describedAs("$template cs").contains("Example s.r.o.").isNotEqualTo(enBody)
            assertThat(enBody).describedAs("$template en").contains("Example s.r.o.")
        }
    }

    @Test
    fun `the push title never carries a variable - nothing about the company or money reaches a lock screen`() {
        ApprovalCopy.TEMPLATES.forEach { template ->
            NotificationLanguage.entries.forEach { lang ->
                val (subject, _) = ApprovalCopy.render(template, vars.filterKeys { it in template.variables }, lang)
                vars.values.forEach { value ->
                    assertThat(subject).describedAs("$template/$lang").doesNotContain(value)
                }
            }
        }
    }

    @Test
    fun `APPROVAL_REQUIRED carries the details a signer needs, in Czech with informal address`() {
        val (subject, body) = ApprovalCopy.render(NotificationTemplate.APPROVAL_REQUIRED, vars, NotificationLanguage.CS)
        assertThat(subject).isEqualTo("Čeká na tvůj podpis")
        assertThat(body).contains("Initiator Example", "platbu na <b>1 000,00 CZK</b>", "pro Payee Example")
            .contains("do 21. 9. 2026 10:15")
    }

    @Test
    fun `null language renders English, as every older template does`() {
        val (subject, _) = ApprovalCopy.render(NotificationTemplate.APPROVAL_EXPIRED, vars, null)
        assertThat(subject).isEqualTo("Request expired")
    }

    @Test
    fun `person-typed values are HTML-escaped and an unknown kind is never echoed`() {
        val hostile = vars + mapOf(
            "entityName" to "<script>x</script>",
            "reason" to "<img src=x onerror=alert(1)>",
            "kind" to "<b>INJECTED</b>",
        )
        val (_, body) = ApprovalCopy.render(NotificationTemplate.APPROVAL_REJECTED, hostile, NotificationLanguage.EN)
        assertThat(body).doesNotContain("<script>", "<img", "INJECTED")
            .contains("&lt;script&gt;", "A request")
    }

    @Test
    fun `a template outside the approval set is refused rather than rendered with the wrong copy`() {
        assertThatThrownBy {
            ApprovalCopy.render(NotificationTemplate.WELCOME, emptyMap(), NotificationLanguage.CS)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
