// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain.model

import com.openbank.notification.domain.HtmlEscape
import java.time.Instant
import java.util.UUID

/** Editorial copy may change; identity, purpose and variable schema remain reviewed in code. */
data class ManagedNotificationTemplate(
    val id: UUID,
    val template: NotificationTemplate,
    val language: NotificationLanguage,
    val channel: NotificationChannel,
    val revision: Long,
    val subject: String,
    val body: String,
    val state: ManagedTemplateState,
    val createdBy: String,
    val createdAt: Instant,
    val publishedBy: String? = null,
    val publishedAt: Instant? = null,
) {
    init {
        require(template in EDITABLE_TEMPLATES) { "template is code-owned and cannot be edited" }
        require(subject.isNotBlank() && subject.length <= MAX_SUBJECT_LENGTH) { "subject must be 1..120 characters" }
        require(body.isNotBlank() && body.length <= MAX_BODY_LENGTH) { "body must be 1..2000 characters" }
        require(createdBy.isNotBlank()) { "creator is required" }
        require(subject.none { it.isISOControl() } && body.none { it.isISOControl() && it != '\n' }) {
            "control characters are not allowed in template copy"
        }
        require(!subject.contains('<') && !subject.contains('>') && !body.contains('<') && !body.contains('>')) {
            "template copy must be plain text"
        }
        require(!LINK.containsMatchIn(subject) && !LINK.containsMatchIn(body)) {
            "links belong to the reviewed deep-link policy"
        }
        require(PLACEHOLDER.findAll(subject).none()) { "subject cannot contain customer variables" }
        require(!subject.contains("{{") && !subject.contains("}}")) { "subject cannot contain placeholders" }
        require(PLACEHOLDER.findAll(body).map { it.groupValues[1] }.toSet() == template.variables) {
            "body placeholders must match the template variable schema"
        }
        require(!body.replace(PLACEHOLDER, "").contains("{{") && !body.replace(PLACEHOLDER, "").contains("}}")) {
            "malformed placeholder"
        }
    }

    fun render(variables: Map<String, String>): Pair<String, String> {
        require(variables.keys == template.variables) { "variables must match the template schema" }
        val escaped = HtmlEscape.escape(body)
        val rendered = PLACEHOLDER.replace(escaped) { match ->
            HtmlEscape.escape(variables.getValue(match.groupValues[1]))
        }
        return subject to "<p>${rendered.replace("\n", "<br>")}</p>"
    }

    companion object {
        const val MAX_SUBJECT_LENGTH = 120
        const val MAX_BODY_LENGTH = 2_000
        private val PLACEHOLDER = Regex("\\{\\{([A-Za-z][A-Za-z0-9]*)}}")
        private val LINK = Regex("(?i)(?:https?://|openbank://|www\\.)")

        // Begin with ordinary account and payment copy. Authorisation, secret and marketing copy
        // retain their existing reviewed implementation until their own policy is migrated.
        val EDITABLE_TEMPLATES: Set<NotificationTemplate> = setOf(
            NotificationTemplate.ACCOUNT_OPENED,
            NotificationTemplate.ACCOUNT_CLOSED,
            NotificationTemplate.TRANSACTION_COMPLETED,
            NotificationTemplate.TRANSACTION_FAILED,
            NotificationTemplate.LOW_BALANCE_ALERT,
        )
    }
}

enum class ManagedTemplateState { DRAFT, PUBLISHED }
