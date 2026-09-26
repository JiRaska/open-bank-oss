// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.domain.model

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Decoupled device approval (ADR-0021). Push/biometric SCA is *out-of-band*: the
 * approval happens on the user's enrolled device, which signs the challenge with a
 * hardware-backed key (Secure Enclave / Android Keystore). The server records the
 * signature-verified decision; [com.openbank.sca.application.usecase.ScaService.verify]
 * consults it instead of auto-approving.
 */

/** Signature algorithm of an enrolled device credential. Maps to a JCA algorithm in the verifier. */
enum class SignatureAlgorithm {
    /** ECDSA P-256 + SHA-256 — WebAuthn ES256, Apple Secure Enclave default. */
    ES256,

    /** Ed25519 (EdDSA). */
    ED25519,
}

/** Outcome the enrolled device asserts for a challenge. */
enum class DeviceDecisionType {
    APPROVED,
    DENIED,
}

/**
 * A device credential enrolled to a party. The public key verifies later assertions;
 * the private key never leaves the device's hardware keystore.
 */
data class EnrolledDevice(
    val id: UUID = UUID.randomUUID(),
    val partyId: UUID,
    /** Stable per-credential identifier the device presents when deciding (e.g. WebAuthn credentialId). */
    val credentialId: String,
    /** Base64-encoded X.509 SubjectPublicKeyInfo (SPKI) of the device public key. */
    val publicKeySpkiB64: String,
    val algorithm: SignatureAlgorithm,
    val createdAt: OffsetDateTime,
)

/**
 * A signature-verified decision recorded against a challenge. Persisted transiently
 * (mirrors the OTP store) — it only needs to outlive the challenge.
 */
data class DeviceApprovalDecision(
    val challengeId: UUID,
    val credentialId: String,
    val decision: DeviceDecisionType,
    /** Base64-encoded signature over [dynamicLinkingPayload]. Retained for audit. */
    val signatureB64: String,
    val decidedAt: OffsetDateTime,
    /**
     * The party whose enrolled device signed this decision (#10281 item 3). Nullable only so a
     * decision written by the previous release (still in Redis for at most a challenge TTL)
     * deserialises; every decision recorded from this release on carries it.
     */
    val decidingPartyId: UUID? = null,
)

/**
 * The exact bytes the device must sign — RTS (EU) 2018/389 Art. 5 **dynamic linking**.
 * Binding the signature to the challenge id, the decision, and the amount+payee means a
 * captured signature cannot be replayed for a different amount, a different creditor, or
 * to flip a DENIED into an APPROVED. Null linking fields collapse to empty segments so the
 * format stays stable for login/consent challenges that carry no payment context.
 *
 * The [DynamicLinkingData.documentSha256]/[DynamicLinkingData.ceremonyId] segments (ADR-0169
 * D2) are appended ONLY when at least one is present. This is deliberate, not an oversight: an
 * unconditional 8-field format would change the signed bytes for every EXISTING purpose
 * (payment/login/consent/etc.) too, breaking signature verification for every already-deployed
 * app build the moment this ships — a live-payment regression, not a document-signing one. Since
 * no purpose has ever populated these two fields before now, appending them only when non-null
 * is 100% byte-identical to the old format for every challenge that existed before ADR-0169.
 *
 * The [DynamicLinkingData.cardId]/[DynamicLinkingData.cardAction] segments extend
 * [ScaPurpose.CARD_MANAGEMENT] under exactly the same rule and for exactly the same reason: a
 * card challenge appends `|cardId|cardAction`, and payment/document/login payloads keep the bytes
 * they already had. Note the two optional pairs are positionally interchangeable when only one is
 * present (a card payload and a document payload are both 8 segments). That is not a bypass: the
 * challenge id is segment 1 and is unique per challenge, so a signature is only ever verified
 * against the one challenge whose stored linking data produced those bytes, and `consume`
 * compares the document and card fields independently rather than positionally.
 *
 * An [ScaPurpose.APPROVAL] challenge signs its own canonical form instead — see
 * [approvalLinkingPayload]. No challenge of any other purpose ever carries approval fields, so
 * every payload that existed before keeps its bytes.
 */
fun ScaChallenge.dynamicLinkingPayload(decision: DeviceDecisionType): ByteArray {
    if (purpose == ScaPurpose.APPROVAL) return approvalLinkingPayload(decision)
    val dl = dynamicLinkingData
    val segments = listOf(
        id.toString(),
        decision.name,
        dl?.amount.orEmpty(),
        dl?.currency.orEmpty(),
        dl?.creditorIban.orEmpty(),
        dl?.reference.orEmpty(),
    ) + optionalPair(dl?.documentSha256, dl?.ceremonyId) + optionalPair(dl?.cardId, dl?.cardAction)
    return segments.joinToString("|").toByteArray(Charsets.UTF_8)
}

/**
 * The bytes a device signs for an [ScaPurpose.APPROVAL] challenge (#10281) — the canonical form the
 * app builds in `approvalDynamicLink`:
 *
 * ```
 * <challengeId>|<decision>|APPROVAL|<approvalRequestId>|<payloadSha256>                                   (non-payment)
 * <challengeId>|<decision>|APPROVAL|<approvalRequestId>|<payloadSha256>|<amount>|<currency>|<creditorIban> (payment)
 * ```
 *
 * Canonicalisation, applied to what was stored at initiate so the app and this service can never
 * disagree on formatting:
 *  - `payloadSha256`: lower-case hex;
 *  - `amount`: plain decimal, dot separator, exactly two fractional digits (`1000` → `1000.00`,
 *    `250.5` → `250.50`); an amount that needs more than two digits is refused at initiate;
 *  - `currency`: upper-case ISO code;
 *  - `creditorIban`: upper-case, all spaces removed.
 *
 * The payment segments are present iff the challenge carries an amount (a PAYMENT approval).
 * `reference` and `creditorName` are display-only and never signed here: the payload hash already
 * binds the whole frozen instruction.
 */
fun ScaChallenge.approvalLinkingPayload(decision: DeviceDecisionType): ByteArray {
    val dl = dynamicLinkingData
    val segments = mutableListOf(
        id.toString(),
        decision.name,
        ScaPurpose.APPROVAL.name,
        dl?.approvalRequestId.orEmpty(),
        dl?.payloadSha256.orEmpty().lowercase(),
    )
    dl?.amount?.let { amount ->
        segments += canonicalAmount(amount)
        segments += dl.currency.orEmpty().uppercase()
        segments += dl.creditorIban.orEmpty().replace(" ", "").uppercase()
    }
    return segments.joinToString("|").toByteArray(Charsets.UTF_8)
}

/** Plain decimal with exactly two fractional digits; throws for a malformed or over-precise amount. */
fun canonicalAmount(amount: String): String =
    java.math.BigDecimal(amount.trim()).setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString()

/**
 * One conditionally-appended dynamic-linking pair: both segments or neither, never one. An absent
 * half of a present pair collapses to an empty segment (NOT the literal "null"), so the payload a
 * half-populated pair produces is still a fixed, unambiguous field count.
 */
private fun optionalPair(first: String?, second: String?): List<String> =
    if (first == null && second == null) emptyList() else listOf(first.orEmpty(), second.orEmpty())
