// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain

import com.openbank.notification.domain.model.NotificationLanguage
import com.openbank.notification.domain.model.NotificationTemplate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class PensionCopyTest {

    /** The exact variable sets pension-service's ParticipantNotificationKind sends (#12392). */
    private val producerContract = mapOf(
        NotificationTemplate.PENSION_PAYOUT_ACCOUNT_CHANGED to setOf("contractId", "accountLast4", "effectiveFrom"),
        NotificationTemplate.PENSION_PAYOUT_EXECUTED to
            setOf("contractId", "purpose", "amount", "currency", "accountLast4"),
        NotificationTemplate.PENSION_STRATEGY_CHANGE_EFFECTIVE to setOf("contractId", "strategyCode", "effectiveFrom"),
        NotificationTemplate.PENSION_TRANSFER_STATUS to setOf("contractId", "direction", "status"),
        NotificationTemplate.PENSION_INCENTIVE_RECEIVED to setOf("contractId", "period", "amount", "currency"),
        NotificationTemplate.PENSION_INCENTIVE_RETURNED to setOf("contractId", "period", "amount", "currency"),
    )

    @Test
    fun `the template vocabulary accepts exactly what pension-service sends`() {
        assertThat(PensionCopy.TEMPLATES).isEqualTo(producerContract.keys)
        producerContract.forEach { (template, vars) -> assertThat(template.variables).isEqualTo(vars) }
    }

    @TestFactory
    fun `every pension template renders every variable in both languages with a constant subject`() =
        producerContract.flatMap { (template, vars) ->
            listOf(NotificationLanguage.CS, NotificationLanguage.EN, null).map { language ->
                DynamicTest.dynamicTest("$template $language") {
                    val values = vars.associateWith { "V-$it" }
                    val (subject, body) = PensionCopy.render(template, values, language)
                    values.values.forEach { assertThat(body).contains(it) }
                    // The push title is lock-screen visible: it never carries a value.
                    values.values.forEach { assertThat(subject).doesNotContain(it) }
                    assertThat(PensionCopy.renderOrNull(template, values, language)).isEqualTo(subject to body)
                }
            }
        }

    @Test
    fun `czech and english copy differ and null language is english`() {
        val vars = mapOf("contractId" to "C1", "accountLast4" to "1234", "effectiveFrom" to "2026-11-01")
        val t = NotificationTemplate.PENSION_PAYOUT_ACCOUNT_CHANGED
        val cs = PensionCopy.render(t, vars, NotificationLanguage.CS)
        assertThat(cs.first).isEqualTo("Změna účtu pro výplatu penze")
        assertThat(PensionCopy.render(t, vars, null)).isEqualTo(PensionCopy.render(t, vars, NotificationLanguage.EN))
        assertThat(cs).isNotEqualTo(PensionCopy.render(t, vars, NotificationLanguage.EN))
    }

    @Test
    fun `interpolated values are html escaped`() {
        val (_, body) = PensionCopy.render(
            NotificationTemplate.PENSION_TRANSFER_STATUS,
            mapOf("contractId" to "<script>", "direction" to "OUT", "status" to "DONE"),
            NotificationLanguage.EN,
        )
        assertThat(body).doesNotContain("<script>").contains("&lt;script&gt;")
    }

    @Test
    fun `non-pension templates are not its business`() {
        assertThat(PensionCopy.renderOrNull(NotificationTemplate.WELCOME, emptyMap(), null)).isNull()
        assertThatThrownBy { PensionCopy.render(NotificationTemplate.WELCOME, emptyMap(), null) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
