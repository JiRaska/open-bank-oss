// SPDX-License-Identifier: AGPL-3.0-only
package com.openbank.communication.domain

/** Copy changes wording only. State, actions and payment safety instructions stay in code. */
object UiMessages {
    private const val MAX_LENGTH = 240
    val keys = setOf(
        "status.loading", "status.unavailable", "err.loadAccounts", "err.staleAccounts", "err.session",
        "prod.loading", "prod.unavailable", "prod.retry", "send.processing", "send.accepted.sub",
    )

    fun validate(personaKey: String, messages: Map<String, String>) {
        require(messages.isEmpty() || personaKey == "customer-copilot") { "UI copy belongs to customer-copilot" }
        require(messages.size <= keys.size * 2) { "Too many UI messages" }
        messages.forEach { (key, value) ->
            require(key.substringBefore('.') in setOf("cs", "en") && key.substringAfter('.') in keys) {
                "Unknown UI message: $key"
            }
            require(value.isNotBlank() && value.length <= MAX_LENGTH) { "Invalid length: $key" }
            require(value.none { it == '<' || it == '>' || it == '{' || it == '}' || it.isISOControl() }) {
                "Use plain text without markup, placeholders or control characters: $key"
            }
        }
    }
}
