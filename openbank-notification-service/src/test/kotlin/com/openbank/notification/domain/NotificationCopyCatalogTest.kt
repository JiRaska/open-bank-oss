// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain

import com.openbank.notification.domain.model.NotificationLanguage
import com.openbank.notification.domain.model.NotificationTemplate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class NotificationCopyCatalogTest {

    /** Every template, in cs and en: real copy, never a blank subject/body and never a raw moustache. */
    @TestFactory
    fun `every template resolves copy in cs and en`() = NotificationTemplate.entries.flatMap { template ->
        listOf(NotificationLanguage.CS, NotificationLanguage.EN).map { language ->
            DynamicTest.dynamicTest("$template $language") {
                val vars = template.variables.associateWith { "V-$it" }
                val (subject, body) = NotificationCopyCatalog.render(template, vars, language)
                assertThat(subject).isNotBlank()
                assertThat(body).isNotBlank().doesNotContain("{{")
            }
        }
    }

    @Test
    fun `localized templates route to their domain copy in the request language`() {
        val pension = mapOf("contractId" to "C1", "accountLast4" to "1234", "effectiveFrom" to "2026-11-01")
        val t = NotificationTemplate.PENSION_PAYOUT_ACCOUNT_CHANGED
        assertThat(NotificationCopyCatalog.render(t, pension, NotificationLanguage.CS))
            .isEqualTo(PensionCopy.render(t, pension, NotificationLanguage.CS))
        val approval = mapOf("entityName" to "Acme", "kind" to "PAYMENT")
        val a = NotificationTemplate.APPROVAL_COMPLETED
        assertThat(NotificationCopyCatalog.render(a, approval, NotificationLanguage.CS))
            .isEqualTo(ApprovalCopy.render(a, approval, NotificationLanguage.CS))
        assertThat(NotificationCopyCatalog.render(t, pension, null))
            .isEqualTo(NotificationCopyCatalog.render(t, pension, NotificationLanguage.EN))
    }
}
