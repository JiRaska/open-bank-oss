// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Same closed-schema shape as [NotificationTemplate] (issue #1325) — see
 * [TemplateVariableSchemaTest] for the equivalent coverage on that enum. The property worth
 * re-proving here is disjointness from [NotificationTemplate]: no [OperatorMessageTemplate]
 * name collides with a system-lifecycle constant such as `ACCOUNT_FROZEN`, several of which
 * feed the oversight webhook (`OversightWebhook.OVERSIGHT_TEMPLATES`) — the reason this enum
 * exists separately at all, not just a naming nicety.
 */
class OperatorMessageTemplateTest {

    @Test
    fun `an undeclared variable is rejected`() {
        val unknown = OperatorMessageTemplate.GENERIC_NOTICE.unknownVariables(
            mapOf("subject" to "Hello", "note" to "Following up", "code" to "483920"),
        )
        assertThat(unknown).containsExactly("code")
    }

    @Test
    fun `a well-formed request has no unknown variables`() {
        assertThat(
            OperatorMessageTemplate.SUPPORT_FOLLOWUP.unknownVariables(mapOf("ticketReference" to "TCK-123")),
        ).isEmpty()
    }

    @Test
    fun `no name collides with a system-lifecycle NotificationTemplate constant`() {
        val systemNames = NotificationTemplate.entries.map { it.name }.toSet()
        val operatorNames = OperatorMessageTemplate.entries.map { it.name }.toSet()
        assertThat(operatorNames intersect systemNames).isEmpty()
    }
}
