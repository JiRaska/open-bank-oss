// SPDX-License-Identifier: AGPL-3.0-only
package com.openbank.communication.domain

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class UiMessagesTest {
    @Test
    fun `short Czech and English profile-change introductions are allowed`() {
        UiMessages.validate(
            "customer-copilot",
            mapOf(
                "cs.send.profileChanged" to "Změnili jste profil.",
                "en.send.profileChanged" to "Your profile changed.",
                "cs.so.err.profileChanged" to "Jste v jiném profilu.",
                "en.so.err.profileChanged" to "You switched profiles.",
            ),
        )
    }

    @Test
    fun `empty unsafe and complete safety messages cannot be saved as editable introductions`() {
        listOf(
            "" to "cs.send.profileChanged",
            "<script>" to "en.send.profileChanged",
            "Your profile changed. Review and confirm the payment again." to "en.send.profileChanged",
            "Změnili jste profil. Zkontrolujte příkaz a potvrďte ho znovu." to "cs.so.err.profileChanged",
            "A".repeat(121) + "." to "en.so.err.profileChanged",
        ).forEach { (value, key) ->
            assertThatThrownBy { UiMessages.validate("customer-copilot", mapOf(key to value)) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
