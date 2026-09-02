// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain.model

import java.time.Instant
import java.util.UUID

// SMS and IN_APP were declared but never implemented — the dispatch `when` in NotificationConsumer
// only logged and returned success for either, so a caller requesting them got silent non-delivery
// with no error (issue #2372). Removed rather than fixed: IN_APP needs a terminal status transition
// and a wake-signal design, SMS needs a real provider port — both are real builds, not something
// this narrowing should speculatively half-do.
enum class NotificationChannel { EMAIL, PUSH }
enum class NotificationStatus { PENDING, SENT, FAILED, BOUNCED }

/**
 * A message template and — inseparably — the complete set of variables it accepts.
 *
 * The schema is a constructor argument rather than a lookup table beside the enum, and that is
 * the whole point: a new constant cannot be added without declaring its variables, because the
 * compiler demands the argument. A side table can silently gain a constant it has no entry for;
 * this cannot (ADR-0176 D1).
 *
 * [variables] is a CLOSED set. `NotificationConsumer` rejects a request carrying any key not
 * listed here, which is what stops a secret-shaped variable riding an ordinary template into
 * storage — `ACCOUNT_FROZEN` with a `code` variable used to render and persist that code in
 * cleartext (issue #1325). Secrecy is a property of the variables, not of the template, so the
 * defence has to live here and not only in [TemplateSensitivity].
 *
 * Keep this in step with `NotificationConsumer.renderTemplate`: a variable declared but never
 * rendered is dead, and a variable rendered but not declared cannot arrive.
 */
enum class NotificationTemplate(val variables: Set<String>) {
    ACCOUNT_OPENED(setOf("accountNumber")),
    ACCOUNT_CLOSED(setOf("accountNumber")),
    ACCOUNT_FROZEN(setOf("accountNumber", "reason")),
    TRANSACTION_COMPLETED(setOf("amount", "currency")),
    TRANSACTION_FAILED(setOf("amount", "currency", "reason")),
    KYC_APPROVED(emptySet()),
    KYC_REJECTED(setOf("reason")),
    KYC_DOCUMENT_REQUIRED(setOf("documentType")),
    CONSENT_GRANTED(setOf("scope")),
    CONSENT_REVOKED(setOf("scope")),
    OTP_CODE(setOf("code")),
    PASSWORD_RESET(setOf("resetLink")),
    WELCOME(setOf("name")),

    /** Decoupled/push SCA — "you have a payment to approve" (#4). [detail] = the human summary. */
    SCA_APPROVAL(setOf("detail")),

    /**
     * ADR-0200/0201 first-slice marketing template: a product/offer communication composed by
     * campaign-service from catalogue variables only. The closed variable schema is the ADR-0176 D4
     * discipline — a campaign supplies values, never free-form body text.
     */
    MARKETING_PRODUCT_OFFER(setOf("offerTitle", "offerText", "ctaText")),

    // ── ADR-0232 delegated-access lifecycle — sent to the party actionable on each transition.
    // `resourceType` is the only detail carried on `openbank.delegation.events` that is safe to
    // put in a template: neither party's display name rides the wire (DelegationEvents.kt has no
    // name field — delegation-service's own counterparty-names table is a read-model local to that
    // service), so DelegationNotificationConsumer cannot render one without an extra cross-service
    // call this fan-out deliberately does not make (see its KDoc).

    /** A grantee has an offer waiting to accept or decline (DelegationOffered). */
    DELEGATION_OFFERED(setOf("resourceType")),

    /** The grantor's offer was accepted and the grant is now active (DelegationActivated). */
    DELEGATION_ACCEPTED(setOf("resourceType")),

    /** The grantee declined the grantor's offer (DelegationDeclined). */
    DELEGATION_DECLINED(setOf("resourceType")),

    /** The grantor revoked an active grant; the grantee's access ends now (DelegationRevoked). */
    DELEGATION_REVOKED(setOf("resourceType")),

    /** The bank temporarily removed delegated authority (DelegationSuspended). */
    DELEGATION_SUSPENDED(setOf("resourceType")),

    /** The bank restored previously suspended delegated authority (DelegationReinstated). */
    DELEGATION_REINSTATED(setOf("resourceType")),

    /** The grantee gave up delegated authority (DelegationRenounced). */
    DELEGATION_RENOUNCED(setOf("resourceType")),

    /** A grant's validity window ended on its own; sent to both parties (DelegationExpired). */
    DELEGATION_EXPIRED(setOf("resourceType")),

    /**
     * ADR-0249 D4 — the grantee spent on the grantor's authority for the FIRST time
     * (DelegationFirstUsed, issue #5728). Sent to the **grantor**, who is the party that cannot
     * otherwise see the consequence of what they granted: the eight templates above all announce a
     * change to the AUTHORITY, and none of them fires when the authority is merely exercised.
     *
     * SECURITY category, so it cannot be silenced: "someone started spending on my account" is the
     * signal a customer needs to catch a grant they did not mean to give, and a preference toggle
     * over it would make the disclosure optional.
     *
     * `resourceType` only, matching its siblings — the event also carries the amount, and it is
     * deliberately NOT rendered here. A push body reaches a lock screen (ADR-0135 §3), and the
     * first thing a delegate spends is not something to put there when the deep link already opens
     * the authenticated app on the grant.
     */
    DELEGATION_FIRST_USED(setOf("resourceType")),
    ;

    /** Keys in [vars] that this template does not accept. Empty = the request is well-formed. */
    fun unknownVariables(vars: Map<String, String>): Set<String> = vars.keys - variables

    /**
     * Owner-approved no-device fallback policy (#4363). This is intentionally part of the closed
     * template model rather than a free-form configuration map: adding a template forces an
     * explicit delivery decision in review. `null` means the existing in-app-feed-only behaviour
     * remains correct.
     *
     * A fallback e-mail never contains the rendered notification body. It is a generic prompt to
     * open the authenticated app, so a missing device cannot turn lock-screen-safe push content
     * into unbounded e-mail PII (ADR-0135 §3).
     */
    val noDeviceFallbackChannel: NotificationChannel?
        get() = when (this) {
            ACCOUNT_FROZEN,
            KYC_REJECTED,
            KYC_DOCUMENT_REQUIRED,
            TRANSACTION_FAILED,
            -> NotificationChannel.EMAIL
            ACCOUNT_OPENED,
            ACCOUNT_CLOSED,
            TRANSACTION_COMPLETED,
            KYC_APPROVED,
            CONSENT_GRANTED,
            CONSENT_REVOKED,
            OTP_CODE,
            PASSWORD_RESET,
            WELCOME,
            SCA_APPROVAL,
            MARKETING_PRODUCT_OFFER,
            DELEGATION_OFFERED,
            DELEGATION_ACCEPTED,
            DELEGATION_DECLINED,
            DELEGATION_REVOKED,
            DELEGATION_SUSPENDED,
            DELEGATION_REINSTATED,
            DELEGATION_RENOUNCED,
            DELEGATION_EXPIRED,
            DELEGATION_FIRST_USED,
            -> null
        }

    /**
     * The customer-facing category a template belongs to (#2). SECURITY is deliberately un-mutable:
     * a customer can never silence OTP / SCA / KYC / account-freeze pushes, so those are always sent
     * regardless of preferences. Everything else maps to a togglable category.
     */
    val category: NotificationCategory
        get() = when (this) {
            OTP_CODE, PASSWORD_RESET, ACCOUNT_FROZEN, SCA_APPROVAL,
            KYC_APPROVED, KYC_REJECTED, KYC_DOCUMENT_REQUIRED,
            CONSENT_GRANTED, CONSENT_REVOKED,
            DELEGATION_OFFERED, DELEGATION_ACCEPTED, DELEGATION_DECLINED,
            DELEGATION_REVOKED, DELEGATION_SUSPENDED, DELEGATION_REINSTATED,
            DELEGATION_RENOUNCED, DELEGATION_EXPIRED, DELEGATION_FIRST_USED,
            -> NotificationCategory.SECURITY
            TRANSACTION_COMPLETED, TRANSACTION_FAILED -> NotificationCategory.PAYMENTS
            ACCOUNT_OPENED, ACCOUNT_CLOSED, WELCOME -> NotificationCategory.PRODUCT
            MARKETING_PRODUCT_OFFER -> NotificationCategory.MARKETING
        }
}

/** Customer-facing notification categories for push preferences (#2). */
enum class NotificationCategory { SECURITY, PAYMENTS, PRODUCT, MARKETING }

data class Notification(
    val id: UUID,
    val partyId: UUID,
    val channel: NotificationChannel,
    val template: NotificationTemplate,
    val recipient: String,
    val subject: String?,
    val body: String,
    val status: NotificationStatus,
    val metadata: Map<String, String>,
    val sentAt: Instant?,
    val createdAt: Instant,
)

data class NotificationRequest(
    val partyId: UUID,
    val channel: NotificationChannel,
    val template: NotificationTemplate,
    val recipient: String,
    val variables: Map<String, String>,
    /**
     * Producer-owned correlation id (ADR-0239 D1). Optional and additive: a producer that wants to
     * hear back what became of this request sets an identifier IT owns — campaign-service uses the
     * send-log row id — and notification-service echoes it, unchanged, on the persisted row and on
     * every [NotificationOutcomeEvent] for it. Producers that do not care omit it and nothing
     * changes for them.
     *
     * Nullable rather than generated here on purpose: an id minted by notification-service would be
     * meaningless to the producer, which is the only party that can join it back to its own row.
     */
    val correlationId: UUID? = null,
    /** Optional bank-owned app route for a PUSH tap; never a template variable. */
    val deepLink: String? = null,
    /**
     * Opaque producer-owned reference supplied only for a PUSH interaction. It is passed to the
     * device as routing metadata and is not persisted as notification content or emitted in a
     * delivery outcome. campaign-service currently supplies its send-log id (issue #4480).
     */
    val interactionRef: UUID? = null,
)

/** Closed allow-list for navigation metadata sent through FCM/APNs. */
object MobileDeepLink {
    private const val DELEGATION_DETAIL_PREFIX = "openbank://delegations/"

    private val allowed = setOf(
        "openbank://home",
        "openbank://savings",
        "openbank://cards",
        "openbank://payments",
        "openbank://products",
    )

    fun isAllowed(value: String?): Boolean = value == null || value in allowed || isCanonicalDelegationDetail(value)

    private fun isCanonicalDelegationDetail(value: String): Boolean {
        if (!value.startsWith(DELEGATION_DETAIL_PREFIX)) return false
        val id = value.removePrefix(DELEGATION_DETAIL_PREFIX)
        return runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
    }
}
