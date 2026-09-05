// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain.model

// ── Secret-bearing templates — the storage redaction core ────────────────────
//
// Some templates render a short-lived authentication secret into the message
// body (the OTP code itself, a password-reset token). Delivering that secret to
// the customer is the whole point of the message; *persisting* it is not.
//
// A stored OTP defeats SCA (ADR-0021): `notifications.body` is readable by any
// ROLE_OPERATOR — both via @RolesAllowed on NotificationResource and via the
// shared rest.rego `operator-read-any` rule, which grants `.read`/`.list` on any
// resource to every operator. Staff who can read a customer's OTP can complete
// that customer's strong authentication. It is also GDPR Art. 5(1)(c) data
// minimisation: the secret has no purpose after dispatch, yet the row is kept
// under the service's declared retention (governance.yaml).
//
// So: render the secret, deliver it, store a placeholder. This is a POSITIVE
// allow-list of the templates that must never reach storage in rendered form —
// the same shape as the ADR-0059 oversight allow-list, and TemplateSensitivityTest
// forbids it drifting.
//
// NOTE: this is the storage sink only. The push-payload sink (sendPush feeds the
// same rendered body into the APNs/FCM message, which ADR-0135 §3 forbids) is a
// separate defect tracked on its own — fixing it needs the customer app's
// fetch-on-tap path, which lives in another repository.

/**
 * Classifies [NotificationTemplate]s by whether their rendered body embeds an
 * authentication secret, and supplies the value stored in place of one.
 */
object TemplateSensitivity {

    /** Stored in `notifications.body` instead of a rendered secret. Not a valid message body. */
    const val REDACTED_BODY: String =
        "[REDACTED] Secret-bearing template: the rendered body is delivered to the customer " +
            "but never stored (GDPR Art. 5(1)(c); a stored OTP would defeat SCA, ADR-0021)."

    /**
     * Templates whose rendered body contains a secret that authenticates the customer.
     *
     * Membership is decided by one question: *if a bank operator read this body, could
     * they use it to act as the customer?* Add a template here the moment its rendered
     * form carries a code, token, link with an embedded token, or password.
     */
    val SECRET_TEMPLATES: Set<NotificationTemplate> = setOf(
        NotificationTemplate.OTP_CODE,
    )

    /** True when [template]'s rendered body embeds an authentication secret. */
    fun isSecret(template: NotificationTemplate): Boolean = template in SECRET_TEMPLATES

    /**
     * The body to persist for [template]: [renderedBody] for ordinary templates,
     * [REDACTED_BODY] for secret-bearing ones. Callers must still deliver the
     * rendered body — only the stored copy is redacted.
     */
    fun bodyForStorage(template: NotificationTemplate, renderedBody: String): String =
        if (isSecret(template)) REDACTED_BODY else renderedBody
}
