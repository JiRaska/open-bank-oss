// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain

import com.openbank.notification.domain.model.NotificationLanguage
import com.openbank.notification.domain.model.NotificationTemplate
import com.openbank.notification.domain.model.NotificationTemplate.APPROVAL_COMPLETED
import com.openbank.notification.domain.model.NotificationTemplate.APPROVAL_EXPIRED
import com.openbank.notification.domain.model.NotificationTemplate.APPROVAL_REJECTED
import com.openbank.notification.domain.model.NotificationTemplate.APPROVAL_REQUIRED
import com.openbank.notification.domain.model.NotificationTemplate.PAYMENT_RELEASE_FAILED
import com.openbank.notification.domain.model.NotificationTemplate.PENSION_INCENTIVE_RECEIVED
import com.openbank.notification.domain.model.NotificationTemplate.PENSION_INCENTIVE_RETURNED
import com.openbank.notification.domain.model.NotificationTemplate.PENSION_PAYOUT_ACCOUNT_CHANGED
import com.openbank.notification.domain.model.NotificationTemplate.PENSION_PAYOUT_EXECUTED
import com.openbank.notification.domain.model.NotificationTemplate.PENSION_STRATEGY_CHANGE_EFFECTIVE
import com.openbank.notification.domain.model.NotificationTemplate.PENSION_TRANSFER_STATUS

/**
 * The single copy lookup for every [NotificationTemplate] (#12392), extracted from
 * NotificationConsumer so the consumer stays small while the lookup stays EXHAUSTIVE: there is
 * no `else` anywhere on this path, so a template without copy is a compile error, never a
 * send-time failure. Templates with Czech copy (approval #10281, pension #12392) are rendered by
 * their domain object in the request language; the rest have English copy only.
 */
object NotificationCopyCatalog {

    /**
     * Renders [template] into a (subject, body) pair in [language] (`null` = English). Approval
     * (#10281) and pension (#12392) templates have Czech copy; every other template is English only
     * and ignores [language].
     *
     * Exhaustive by design — there is deliberately **no `else` branch**. The `else` this replaces
     * dumped every caller-supplied variable into the body (`"$key: $value"`), which is how a
     * secret could ride an ordinary template into storage (issue #1325), and it silently absorbed
     * the seven constants nobody had written a render for: they reached real customers as raw
     * variable dumps. Without the `else`, adding a constant to [NotificationTemplate] is a
     * COMPILE error here until someone writes its copy — a guard that cannot be forgotten, unlike
     * the review the classification allow-list depends on (ADR-0176 D1).
     *
     * Every `vars[...]` key read here must be declared in that constant's [NotificationTemplate.variables];
     * anything else is rejected upstream and can never arrive.
     */
    // one branch per template — grows with the catalogue; each branch stays a two-line render
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun render(
        template: NotificationTemplate,
        vars: Map<String, String>,
        language: NotificationLanguage?,
    ): Pair<String, String> = when (template) {
        APPROVAL_REQUIRED, APPROVAL_COMPLETED, APPROVAL_REJECTED, APPROVAL_EXPIRED, PAYMENT_RELEASE_FAILED ->
            ApprovalCopy.render(template, vars, language)
        PENSION_PAYOUT_ACCOUNT_CHANGED, PENSION_PAYOUT_EXECUTED, PENSION_STRATEGY_CHANGE_EFFECTIVE,
        PENSION_TRANSFER_STATUS, PENSION_INCENTIVE_RECEIVED, PENSION_INCENTIVE_RETURNED,
        -> PensionCopy.render(template, vars, language)
        NotificationTemplate.ACCOUNT_OPENED ->
            "Your OpenBank account is ready" to
                "<h2>Welcome to OpenBank!</h2><p>Your account <b>${vars.v(
                    "accountNumber",
                )}</b> has been opened.</p>"
        NotificationTemplate.ACCOUNT_CLOSED ->
            "Your OpenBank account has been closed" to
                "<h2>Account Closed</h2><p>Your account <b>${vars.v("accountNumber")}</b> has been closed. " +
                "Statements and transaction history remain available on request.</p>"
        NotificationTemplate.ACCOUNT_FROZEN ->
            "Your OpenBank account has been frozen" to
                "<h2>Account Frozen</h2><p>Access to your account <b>${vars.v("accountNumber")}</b> has been " +
                "temporarily suspended. Reason: ${vars.v("reason")}. Please contact support.</p>"
        NotificationTemplate.TRANSACTION_COMPLETED ->
            "Transaction completed" to
                "<p>Transaction of <b>${vars.v("amount")} ${vars.v("currency")}</b> completed successfully.</p>"
        NotificationTemplate.TRANSACTION_FAILED ->
            "Transaction failed" to
                "<h2>Transaction Failed</h2><p>Your transaction of <b>${vars.v("amount")} " +
                "${vars.v("currency")}</b> could not be completed. Reason: ${vars.v("reason")}. " +
                "No funds have left your account.</p>"
        NotificationTemplate.KYC_APPROVED ->
            "Identity verification approved" to
                "<h2>KYC Approved</h2><p>Your identity has been verified. You can now use all OpenBank services.</p>"
        NotificationTemplate.KYC_REJECTED ->
            "Identity verification failed" to
                "<h2>KYC Rejected</h2><p>We could not verify your identity. Reason: ${vars.v(
                    "reason",
                )}. Please contact support.</p>"
        NotificationTemplate.CONSENT_GRANTED ->
            "Access to your account data was granted" to
                "<h2>Consent Granted</h2><p>You granted access to your account data " +
                "(<b>${vars.v("scope")}</b>). You can withdraw this at any time in the OpenBank app.</p>"
        NotificationTemplate.CONSENT_REVOKED ->
            "Access to your account data was withdrawn" to
                "<h2>Consent Withdrawn</h2><p>Access to your account data " +
                "(<b>${vars.v("scope")}</b>) has been withdrawn. No further data will be shared under it.</p>"
        NotificationTemplate.OTP_CODE ->
            "Your OpenBank verification code" to
                "<h2>Verification Code</h2><p>Your code is: <b>${vars.v("code")}</b>. Valid for 5 minutes.</p>"
        NotificationTemplate.WELCOME ->
            "Welcome to OpenBank" to
                "<h2>Welcome!</h2><p>Thank you for joining OpenBank, ${vars.v("name")}.</p>"
        NotificationTemplate.SCA_APPROVAL ->
            "Approve your payment" to
                "<p>${vars.v("detail").ifBlank { "You have a payment waiting for your approval." }}</p>"
        NotificationTemplate.MARKETING_PRODUCT_OFFER ->
            vars.v("offerTitle") to
                "<h2>${vars.v("offerTitle")}</h2><p>${vars.v("offerText")}</p>" +
                "<p><b>${vars.v("ctaText")}</b></p>" +
                "<p style=\"font-size:small;color:#666\">You are receiving this because you opted in to " +
                "marketing emails. Manage your preferences in the app.</p>"
        NotificationTemplate.DELEGATION_OFFERED ->
            "You have a delegated access offer to review" to
                "<h2>Delegated Access Offer</h2><p>Someone has offered you delegated access to their " +
                "<b>${vars.v("resourceType")}</b>. Open the OpenBank app to accept or decline.</p>"
        NotificationTemplate.DELEGATION_ACCEPTED ->
            "Your delegated access offer was accepted" to
                "<h2>Offer Accepted</h2><p>Your delegated access offer for your " +
                "<b>${vars.v("resourceType")}</b> was accepted and is now active.</p>"
        NotificationTemplate.DELEGATION_DECLINED ->
            "Your delegated access offer was declined" to
                "<h2>Offer Declined</h2><p>Your delegated access offer for your " +
                "<b>${vars.v("resourceType")}</b> was declined. No access was granted.</p>"
        NotificationTemplate.DELEGATION_REVOKED ->
            "Your delegated access was revoked" to
                "<h2>Access Revoked</h2><p>Delegated access to a <b>${vars.v("resourceType")}</b> " +
                "granted to you has been revoked. You can no longer act on it.</p>"
        NotificationTemplate.DELEGATION_SUSPENDED ->
            "Delegated access was suspended" to
                "<h2>Access Suspended</h2><p>Delegated access for a <b>${vars.v("resourceType")}</b> " +
                "was temporarily suspended by the bank. It cannot be used while suspended.</p>"
        NotificationTemplate.DELEGATION_REINSTATED ->
            "Delegated access was restored" to
                "<h2>Access Restored</h2><p>Delegated access for a <b>${vars.v("resourceType")}</b> " +
                "was restored by the bank and may be used again within its existing scope and conditions.</p>"
        NotificationTemplate.DELEGATION_RENOUNCED ->
            "Delegated access was renounced" to
                "<h2>Access Renounced</h2><p>The person who held delegated access to your " +
                "<b>${vars.v("resourceType")}</b> ended that access. It is no longer active.</p>"
        NotificationTemplate.DELEGATION_EXPIRED ->
            "A delegated access grant has expired" to
                "<h2>Grant Expired</h2><p>A delegated access grant for a <b>${vars.v("resourceType")}</b> " +
                "has reached the end of its validity period and is no longer active.</p>"
        NotificationTemplate.DELEGATION_FIRST_USE ->
            "Delegated access was used for a payment" to
                "<h2>Delegated Access Used</h2><p>A person you authorised used delegated access " +
                "for a confirmed payment. Open the OpenBank app to review your delegated access.</p>"
        NotificationTemplate.DELEGATION_RECERTIFICATION_DUE ->
            "Review delegated access" to
                "<h2>Review Delegated Access</h2><p>A delegated access grant is due for review " +
                "under your <b>${vars.v("audience")}</b> review cadence. Access remains active until " +
                "you explicitly keep, narrow or revoke it in the OpenBank app.</p>"
    }
}

/**
 * Reads a declared template variable, HTML-escaped, or "" when the caller omitted it.
 *
 * The schema is closed against **undeclared** keys, not against missing ones (see
 * [NotificationTemplate.variables]): rejecting a partial request would silently drop a real
 * message, since poison payloads are acked. An omitted variable renders empty, as it always has.
 *
 * Escaping happens HERE, not per call site (issue #1382): every one of [NotificationCopyCatalog]'s ~16
 * reads interpolates straight into an HTML body with zero escaping between a domain-event-supplied
 * variable and the mail actually sent — a `reason`, `documentType`, or `scope` containing markup
 * rendered verbatim in the customer's mail client. Escaping the shared accessor closes every call
 * site in one place instead of relying on each of the 16 to remember it. (The escaper also covers
 * the attribute-value context: `"` and `'` are escaped, so a variable remains safe if a template
 * ever interpolates one into an `href="..."` attribute again — the removed PASSWORD_RESET's
 * `resetLink` was the case that originally forced that, #8568.)
 *
 * Top-level rather than a member so the catalogue reads as copy instead of null-handling — the
 * 16 inline `?: ""` reads it replaces were most of that function's cyclomatic complexity.
 */
private fun Map<String, String>.v(key: String): String = HtmlEscape.escape(this[key] ?: "")
