// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain.model

/**
 * The closed set of messages an operator may send via `POST /api/v1/notifications/messages`
 * (ADR-0176 D2, `opsmessage.compose`).
 *
 * **Deliberately not [NotificationTemplate].** That enum is system-triggered lifecycle content,
 * tightly bound to a real domain event, and some of it is wired into the ADR-0059 oversight
 * webhook: `ACCOUNT_FROZEN`, `KYC_REJECTED`, `TRANSACTION_FAILED` and `CONSENT_REVOKED` fire a
 * Slack alert on send (`OversightWebhook.OVERSIGHT_TEMPLATES`). If `opsmessage.compose` reused
 * that enum, an operator manually sending `ACCOUNT_FROZEN` — to test the feature, or by mistake —
 * would raise a false "account frozen" alert for an account that is not frozen, and every other
 * lifecycle template has the same problem in miniature: its very existence in the notification
 * history implies a domain event that did not happen. This enum holds only generic,
 * pre-reviewed, operator-appropriate copy that makes no domain claim.
 *
 * Same closed-schema shape as [NotificationTemplate] (issue #1325, ADR-0176 D1): variables are a
 * constructor argument, so a new entry cannot be added without declaring what it accepts, and
 * `OperatorMessageConsumer.render` is exhaustive with no `else` — a new constant is a compile
 * error until its copy is written.
 *
 * Intentionally minimal. `ADR-0176 D2`'s versioned, reviewed catalogue — ideally backed by
 * `openbank-document-service`'s `TemplateRepositoryPort` per the ADR's corrected Neutral
 * consequence — is future work (tracked as the remaining slice of the ADR-0176 rollout). This
 * is the smallest closed set that proves the four-eyes write path end-to-end without shipping
 * unreviewed free text.
 */
enum class OperatorMessageTemplate(val variables: Set<String>) {
    GENERIC_NOTICE(setOf("subject", "note")),
    SUPPORT_FOLLOWUP(setOf("ticketReference")),
    ;

    /** Keys in [vars] that this template does not accept. Empty = the request is well-formed. */
    fun unknownVariables(vars: Map<String, String>): Set<String> = vars.keys - variables
}
