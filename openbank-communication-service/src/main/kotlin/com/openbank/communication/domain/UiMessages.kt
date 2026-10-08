// SPDX-License-Identifier: AGPL-3.0-only
package com.openbank.communication.domain

/** Copy changes wording only. State, actions and payment safety instructions stay in code. */
object UiMessages {
    private const val MAX_LENGTH = 240
    val keys = setOf(
        "status.loading", "status.unavailable", "err.loadAccounts", "err.staleAccounts", "err.session",
        "prod.loading", "prod.unavailable", "prod.retry", "send.processing", "send.accepted.sub",
        "send.profileChanged", "so.err.profileChanged",
    )

    /** Editors supply only the introductory sentence; the edge appends the fixed action. */
    private val safetyKeys = setOf("send.profileChanged", "so.err.profileChanged")

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
            if (key.substringAfter('.') in safetyKeys) {
                require(value.trim() == value && value.length <= 120 && value.last() in setOf('.', '!', '?')) {
                    "Profile-change intro must be one short sentence: $key"
                }
                require(value.count { it == '.' || it == '!' || it == '?' } == 1) {
                    "Profile-change intro must contain one sentence only: $key"
                }
            }
        }
    }
}
