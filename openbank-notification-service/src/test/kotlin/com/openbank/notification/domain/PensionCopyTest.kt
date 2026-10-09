// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain

import com.openbank.notification.domain.model.NotificationCategory
import com.openbank.notification.domain.model.NotificationChannel
import com.openbank.notification.domain.model.NotificationLanguage
import com.openbank.notification.domain.model.NotificationTemplate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PensionCopyTest {
    private val values = mapOf(
        "contractId" to "contract-example",
        "accountLast4" to "1234",
        "effectiveFrom" to "2026-10-09",
        "purpose" to "RETIREMENT",
        "amount" to "100.00",
        "currency" to "CZK",
        "strategyCode" to "BALANCED",
        "direction" to "INBOUND",
        "status" to "PENDING",
        "period" to "2026-09",
    )

    @Test
    fun `all six pension templates have exact producer schemas and distinct Czech and English copy`() {
        val expected = mapOf(
            NotificationTemplate.PENSION_PAYOUT_ACCOUNT_CHANGED to setOf("contractId", "accountLast4", "effectiveFrom"),
            NotificationTemplate.PENSION_PAYOUT_EXECUTED to
                setOf("contractId", "purpose", "amount", "currency", "accountLast4"),
            NotificationTemplate.PENSION_STRATEGY_CHANGE_EFFECTIVE to
                setOf("contractId", "strategyCode", "effectiveFrom"),
            NotificationTemplate.PENSION_TRANSFER_STATUS to setOf("contractId", "direction", "status"),
            NotificationTemplate.PENSION_INCENTIVE_RECEIVED to setOf("contractId", "period", "amount", "currency"),
            NotificationTemplate.PENSION_INCENTIVE_RETURNED to setOf("contractId", "period", "amount", "currency"),
        )
        assertThat(PensionCopy.TEMPLATES).containsExactlyInAnyOrderElementsOf(expected.keys)
        expected.forEach { (template, schema) ->
            assertThat(template.variables).isEqualTo(schema)
            assertThat(template.unknownVariables(values)).isEqualTo(values.keys - schema)
            val scoped = values.filterKeys { it in schema }
            val (csSubject, csBody) = PensionCopy.render(template, scoped, NotificationLanguage.CS)
            val (enSubject, enBody) = PensionCopy.render(template, scoped, NotificationLanguage.EN)
            assertThat(csSubject).isNotBlank().isNotEqualTo(enSubject)
            assertThat(csBody).isNotBlank().isNotEqualTo(enBody)
            schema.forEach { key ->
                assertThat(csBody).describedAs("$template cs $key").contains(values.getValue(key))
                assertThat(enBody).describedAs("$template en $key").contains(values.getValue(key))
            }
            values.values.forEach { value ->
                assertThat(csSubject).doesNotContain(value)
                assertThat(enSubject).doesNotContain(value)
            }
        }
    }

    @Test
    fun `account-change notice cannot be muted and has a no-device fallback`() {
        assertThat(NotificationTemplate.PENSION_PAYOUT_ACCOUNT_CHANGED.category)
            .isEqualTo(NotificationCategory.SECURITY)
        assertThat(NotificationTemplate.PENSION_PAYOUT_ACCOUNT_CHANGED.noDeviceFallbackChannel)
            .isEqualTo(NotificationChannel.EMAIL)
    }

    @Test
    fun `copy escapes producer values and never accepts another template`() {
        val vars = values + ("contractId" to "<script>x</script>")
        val (_, body) = PensionCopy.render(
            NotificationTemplate.PENSION_PAYOUT_ACCOUNT_CHANGED, vars, NotificationLanguage.EN,
        )
        assertThat(body).contains("&lt;script&gt;x&lt;/script&gt;").doesNotContain("<script>")
        assertThat(PensionCopy.renderOrNull(NotificationTemplate.WELCOME, values, NotificationLanguage.CS)).isNull()
        assertThatThrownBy { PensionCopy.render(NotificationTemplate.WELCOME, values, NotificationLanguage.EN) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `missing language defaults to English`() {
        assertThat(PensionCopy.render(NotificationTemplate.PENSION_TRANSFER_STATUS, values, null).first)
            .isEqualTo("Pension transfer status")
    }
}
