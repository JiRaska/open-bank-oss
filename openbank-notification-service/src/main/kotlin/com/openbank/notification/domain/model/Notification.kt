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
    CONSENT_GRANTED(setOf("scope")),
    CONSENT_REVOKED(setOf("scope")),

    // No producer, and deliberately kept: it is the only member of TemplateSensitivity's
    // SECRET_TEMPLATES, so deleting it as "unproduced" would silently empty the at-rest redaction
    // guard (#8568). sca-service refuses TOTP outright (#8567) — there is no transport for a code.
    OTP_CODE(setOf("code")),

    // No PASSWORD_RESET template (#8568): no password flow exists anywhere in the system —
    // the app authenticates with passkeys/biometrics and Keycloak runs with
    // resetPasswordAllowed=false and no SMTP, so nothing could ever produce one.

    // No producer (#8568). Kept rather than removed because the onboarding moment it would occupy
    // already carries KYC_APPROVED and then ACCOUNT_OPENED — a third greeting there is a product
    // decision about REPLACING one of those, not a gap to fill.
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

    /** The grantor's delegated authority was used for a confirmed payment for the first time. */
    DELEGATION_FIRST_USE(emptySet()),

    /** A customer must explicitly review an active delegation; no access is changed by this reminder. */
    DELEGATION_RECERTIFICATION_DUE(setOf("audience")),

    // ── Multi-signature business approvals (#10281), raised from `delegation-service`'s
    // approval-events topic by ApprovalNotificationConsumer. Copy is rendered in Czech or English
    // ([NotificationLanguage]) by ApprovalCopy. The push TITLE never carries a variable (it is the
    // lock-screen-visible part, ADR-0135 §3); amount, payee and entity live only in the inbox body,
    // which the app fetches on tap through the authenticated, party-scoped endpoint.
    // `kind` is a closed producer vocabulary (PAYMENT, POLICY_CHANGE, PAYEE_ADD, PAYEE_REMOVE);
    // an unknown kind renders as a generic "request", never verbatim.

    /** A co-signer must sign or reject a pending request (APPROVAL_REQUESTED). SECURITY: never muted. */
    APPROVAL_REQUIRED(setOf("entityName", "kind", "amountFormatted", "payeeName", "initiatorName", "expiresAt")),

    /** Every required signature was collected (APPROVAL_COMPLETED). */
    APPROVAL_COMPLETED(setOf("entityName", "kind", "amountFormatted", "payeeName")),

    /** A signer rejected the request; nothing will be executed (APPROVAL_REJECTED). */
    APPROVAL_REJECTED(setOf("entityName", "kind", "amountFormatted", "payeeName", "reason")),

    /** The request ran out of time before collecting its signatures (APPROVAL_EXPIRED). */
    APPROVAL_EXPIRED(setOf("entityName", "kind", "amountFormatted", "payeeName")),

    /** A fully approved payment was refused by the payment rail at release (PAYMENT_RELEASE_FAILED). */
    PAYMENT_RELEASE_FAILED(setOf("entityName", "amountFormatted", "payeeName", "reason")),
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
            TRANSACTION_FAILED,
            // First delegated spend is a security event of the same severity as ACCOUNT_FROZEN:
            // someone just exercised delegated authority over the grantor's money, and a missing
            // device must not silence that (the fallback carries no body, only the prompt).
            DELEGATION_FIRST_USE,
            // Same class as TRANSACTION_FAILED: an approved payment did NOT leave, and the people
            // who approved it believe it did. The fallback carries no body, only the prompt.
            PAYMENT_RELEASE_FAILED,
            -> NotificationChannel.EMAIL
            ACCOUNT_OPENED,
            ACCOUNT_CLOSED,
            TRANSACTION_COMPLETED,
            KYC_APPROVED,
            CONSENT_GRANTED,
            CONSENT_REVOKED,
            OTP_CODE,
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
            DELEGATION_RECERTIFICATION_DUE,
            APPROVAL_REQUIRED,
            APPROVAL_COMPLETED,
            APPROVAL_REJECTED,
            APPROVAL_EXPIRED,
            -> null
        }

    /**
     * The customer-facing category a template belongs to (#2). SECURITY is deliberately un-mutable:
     * a customer can never silence OTP / SCA / KYC / account-freeze pushes, so those are always sent
     * regardless of preferences. Everything else maps to a togglable category.
     */
    val category: NotificationCategory
        get() = when (this) {
            OTP_CODE, ACCOUNT_FROZEN, SCA_APPROVAL,
            KYC_APPROVED, KYC_REJECTED,
            CONSENT_GRANTED, CONSENT_REVOKED,
            DELEGATION_OFFERED, DELEGATION_ACCEPTED, DELEGATION_DECLINED,
            DELEGATION_REVOKED, DELEGATION_SUSPENDED, DELEGATION_REINSTATED,
            DELEGATION_RENOUNCED, DELEGATION_EXPIRED, DELEGATION_FIRST_USE,
            DELEGATION_RECERTIFICATION_DUE,
            // A signature request is an authorisation step over company money, like SCA_APPROVAL:
            // muting it would silently stall every payment that needs this person's signature.
            APPROVAL_REQUIRED,
            -> NotificationCategory.SECURITY
            TRANSACTION_COMPLETED, TRANSACTION_FAILED,
            APPROVAL_COMPLETED, APPROVAL_REJECTED, APPROVAL_EXPIRED, PAYMENT_RELEASE_FAILED,
            -> NotificationCategory.PAYMENTS
            ACCOUNT_OPENED, ACCOUNT_CLOSED, WELCOME -> NotificationCategory.PRODUCT
            MARKETING_PRODUCT_OFFER -> NotificationCategory.MARKETING
        }
}

/**
 * Language a template is rendered in. Only the approval templates (#10281) carry Czech copy today;
 * every older template has English copy only and ignores this. `null` on a request = [EN], which is
 * what every producer that predates the field has always received.
 */
enum class NotificationLanguage { CS, EN }

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
    /**
     * Optional durable idempotency key for a producer-owned business fact. Unlike
     * [correlationId], a duplicate key deliberately produces no second notification row or send.
     */
    val deduplicationKey: UUID? = null,
    /** Optional bank-owned app route for a PUSH tap; never a template variable. */
    val deepLink: String? = null,
    /**
     * Opaque producer-owned reference supplied only for a PUSH interaction. It is passed to the
     * device as routing metadata and is not persisted as notification content or emitted in a
     * delivery outcome. campaign-service currently supplies its send-log id (issue #4480).
     */
    val interactionRef: UUID? = null,
    /** Rendering language (#10281). Optional and additive; `null` renders English. */
    val language: NotificationLanguage? = null,
)

/** Closed allow-list for navigation metadata sent through FCM/APNs. */
object MobileDeepLink {
    private const val DELEGATION_DETAIL_PREFIX = "openbank://delegations/"
    private const val BUSINESS_APPROVAL_DETAIL_PREFIX = "openbank://business/approvals/"
    private const val ENTITY_QUERY = "?entity="

    private val allowed = setOf(
        "openbank://home",
        "openbank://savings",
        "openbank://cards",
        "openbank://payments",
        "openbank://products",
    )

    fun isAllowed(value: String?): Boolean = value == null ||
        value in allowed ||
        isCanonicalDetail(value, DELEGATION_DETAIL_PREFIX) ||
        isCanonicalBusinessApproval(value)

    /**
     * Builds the approval-detail link (#10281). With [entityPartyId] the app switches straight to
     * that company's profile; without it the app falls back to resolving the entity itself.
     */
    fun businessApproval(approvalId: UUID, entityPartyId: UUID? = null): String =
        BUSINESS_APPROVAL_DETAIL_PREFIX + approvalId + (entityPartyId?.let { ENTITY_QUERY + it } ?: "")

    /**
     * `<prefix><uuid>` optionally followed by exactly `?entity=<uuid>` — one query parameter, that
     * name, a canonical UUID value, nothing after it. Any other parameter, a second one, an
     * encoded or empty value, or a fragment is refused: the link steers device navigation.
     */
    private fun isCanonicalBusinessApproval(value: String): Boolean {
        val queryAt = value.indexOf('?')
        if (queryAt < 0) return isCanonicalDetail(value, BUSINESS_APPROVAL_DETAIL_PREFIX)
        val query = value.substring(queryAt)
        return isCanonicalDetail(value.substring(0, queryAt), BUSINESS_APPROVAL_DETAIL_PREFIX) &&
            query.startsWith(ENTITY_QUERY) &&
            isCanonicalUuid(query.removePrefix(ENTITY_QUERY))
    }

    private fun isCanonicalUuid(id: String): Boolean =
        runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

    /**
     * `<prefix><uuid>` and nothing else: the remainder must round-trip as a canonical lower-case
     * UUID, so a query string, a path suffix, a fragment or an upper-cased id is refused.
     */
    private fun isCanonicalDetail(value: String, prefix: String): Boolean {
        if (!value.startsWith(prefix)) return false
        val id = value.removePrefix(prefix)
        return runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
    }
}
