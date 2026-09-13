// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Executable form of the closed-variable-schema guarantee (ADR-0176 D1, issue #1325): a template
 * accepts exactly the variables it declares, so a secret-shaped value cannot ride an ordinary
 * template into a stored body.
 *
 * The strongest guard for this rule is NOT here — it is the compiler. `renderTemplate`'s `when`
 * has no `else`, so a new constant fails to build until someone writes its copy, and the schema
 * is a constructor argument, so it cannot be added without declaring variables. These tests cover
 * what the type system cannot: that the closed set actually rejects, and that every declared
 * variable is one a real render reads.
 */
class TemplateVariableSchemaTest {

    @Test
    fun `every template declares its schema — an undeclared key is unknown`() {
        // The exact hole #1325 describes: an OPERATIONAL template carrying a secret-shaped key.
        val unknown = NotificationTemplate.ACCOUNT_FROZEN.unknownVariables(
            mapOf("accountNumber" to "CZ65...", "reason" to "AML review", "code" to "483920"),
        )
        assertThat(unknown).containsExactly("code")
    }

    @Test
    fun `a well-formed request has no unknown variables`() {
        assertThat(
            NotificationTemplate.TRANSACTION_COMPLETED.unknownVariables(
                mapOf("amount" to "250.00", "currency" to "CZK"),
            ),
        ).isEmpty()
    }

    @Test
    fun `missing variables are not rejected — only undeclared ones are`() {
        // Deliberate: the schema is closed against ADDITION, lenient about omission. Rejecting a
        // partial request would silently drop a real message (poison payloads are acked), turning
        // a degraded message into no message at all. Undeclared keys are the security property;
        // missing keys are a caller bug that renders an empty placeholder, as it always has.
        assertThat(NotificationTemplate.OTP_CODE.unknownVariables(emptyMap())).isEmpty()
    }

    @Test
    fun `the accepted set is closed — an empty-schema template accepts nothing`() {
        assertThat(NotificationTemplate.KYC_APPROVED.variables).isEmpty()
        assertThat(NotificationTemplate.KYC_APPROVED.unknownVariables(mapOf("code" to "483920")))
            .containsExactly("code")
    }

    @Test
    fun `secret templates declare exactly the variable that carries the secret`() {
        assertThat(NotificationTemplate.OTP_CODE.variables).containsExactly("code")
        // It is also classified SECRET, so its rendered body is delivered but never stored.
        // Two independent controls: the schema stops the secret arriving on the WRONG template,
        // TemplateSensitivity stops it being stored on the RIGHT one.
        assertThat(TemplateSensitivity.SECRET_TEMPLATES).contains(
            NotificationTemplate.OTP_CODE,
        )
    }

    @Test
    fun `no template declares a variable whose name suggests a secret unless it is classified SECRET`() {
        // A cheap, honest heuristic — not a proof. If a future template declares a `code`/`token`/
        // `password`/`secret`/`otp` variable, it is almost certainly secret-bearing and belongs in
        // SECRET_TEMPLATES. Failing here is a prompt to think, not necessarily a bug.
        val secretish = setOf("code", "token", "password", "secret", "otp", "pin", "resetLink")
        for (template in NotificationTemplate.entries) {
            val carriesSecretish = template.variables.any { it in secretish }
            if (carriesSecretish) {
                assertThat(TemplateSensitivity.isSecret(template))
                    .describedAs(
                        "%s declares a secret-shaped variable %s but is not in SECRET_TEMPLATES — " +
                            "its rendered body would be stored in cleartext",
                        template.name,
                        template.variables.filter { it in secretish },
                    )
                    .isTrue()
            }
        }
    }
}
