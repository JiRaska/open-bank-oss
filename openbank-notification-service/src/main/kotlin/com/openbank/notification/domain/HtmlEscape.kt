// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain

/**
 * Minimal HTML-context escaper for template variables interpolated into rendered notification
 * bodies (issue #1382).
 *
 * Neither [com.openbank.notification.application.NotificationConsumer.renderTemplate] nor
 * [com.openbank.notification.application.OperatorMessageService.render] ran any escaping between
 * a caller-supplied variable and the HTML body handed to `Mail.withHtml` — an operator (or, for
 * the system templates, an upstream domain event) could inject arbitrary markup that a customer's
 * mail client would render, including `<img onerror=...>`/`<script>`-style payloads and an
 * attribute breakout via an embedded `"` if a variable is ever interpolated into an
 * `href="..."` attribute (the removed PASSWORD_RESET's `resetLink` was that case, #8568 — the
 * quote escaping stays so a future attribute-context variable is safe by default).
 *
 * Deliberately not a general-purpose sanitizer: every call site here treats its variables as
 * plain text that must render literally, never as markup the caller is allowed to inject, so
 * escaping the five reserved characters is sufficient and correct for both the element-body and
 * attribute-value contexts these templates use.
 */
object HtmlEscape {
    fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
