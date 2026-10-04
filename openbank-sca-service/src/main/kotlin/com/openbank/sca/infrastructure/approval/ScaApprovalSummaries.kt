// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.approval

import com.openbank.sca.domain.model.EnrolledDevice
import com.openbank.sca.domain.model.ScaChallenge
import com.openbank.sca.domain.model.SignatureAlgorithm
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/**
 * What an SCA operator approval shows its checker: the operation the maker's request BOUND,
 * rendered once at approval creation and stored with the approval (#11675 binding, this file's
 * rendering). Same `key=value` shape as the generic summary settlement's checkers read, but built
 * per action so the reviewer can recognise the target without seeing material they must not:
 *
 * - `device.enroll`: party, a short credential handle, the algorithm and the first 8 hex of the
 *   SHA-256 of the public key's SPKI bytes — never the key itself.
 * - `device.revoke`: party, the device as a short handle, its credential handle, algorithm and
 *   enrolment date, or a marker that the party holds no such device.
 * - `scaChallenge.consume`: the challenge's purpose, amount and currency, the creditor masked to
 *   its last 4 characters, and card action / document hash prefix where present.
 *
 * Every value a caller supplied is validated against its expected shape or shortened, so a
 * crafted field cannot dress the summary up as something else. Pure: no I/O, no clock.
 */
object ScaApprovalSummaries {

    const val KEY_FINGERPRINT_HEX = 8
    private const val HANDLE_CHARS = 8
    private const val MASK_VISIBLE = 4
    private const val SHA256_HEX_LENGTH = 64
    private val AMOUNT = Regex("^-?\\d{1,18}(\\.\\d{1,6})?$")
    private val CURRENCY = Regex("^[A-Z]{3}$")
    private val TOKEN = Regex("^[A-Z0-9_]{1,40}$")
    private val HEX = Regex("^[0-9a-fA-F]+$")

    fun enroll(partyId: UUID, credentialId: String, publicKey: String, algorithm: SignatureAlgorithm): String =
        "action=device.enroll party=$partyId credential=${handle(credentialId)} algorithm=${algorithm.name} " +
            "keySha256=${keyFingerprint(publicKey)}"

    fun revoke(partyId: UUID, deviceId: UUID, device: EnrolledDevice?): String {
        val head = "action=device.revoke party=$partyId device=${handle(deviceId.toString())}"
        if (device == null) return "$head target=not-a-device-of-this-party"
        val state = if (device.revokedAt != null) " state=already-revoked" else ""
        return "$head credential=${handle(device.credentialId)} algorithm=${device.algorithm.name} " +
            "enrolledAt=${device.createdAt.toLocalDate()}$state"
    }

    @Suppress("LongParameterList")
    fun consume(
        challengeId: UUID,
        partyId: UUID,
        challenge: ScaChallenge?,
        amount: String?,
        currency: String?,
        creditor: String?,
        cardAction: String?,
        documentSha256: String?,
    ): String = buildList {
        add("action=scaChallenge.consume")
        add("challenge=${handle(challengeId.toString())}")
        add("purpose=${challenge?.purpose?.name ?: "unknown-challenge"}")
        add("party=$partyId")
        if (amount != null || currency != null) {
            add("amount=${shaped(amount, AMOUNT)} ${shaped(currency, CURRENCY)}")
        }
        if (creditor != null) add("creditor=${mask(creditor)}")
        if (cardAction != null) add("cardAction=${shaped(cardAction, TOKEN)}")
        if (documentSha256 != null) add("documentSha256=${hexPrefix(documentSha256)}")
    }.joinToString(" ")

    /** First [KEY_FINGERPRINT_HEX] hex of SHA-256 over the decoded SPKI bytes; never the key. */
    fun keyFingerprint(publicKey: String): String {
        val bytes = runCatching { Base64.getDecoder().decode(publicKey.trim()) }.getOrNull()
            ?: return "undecodable"
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return HexFormat.of().formatHex(digest).take(KEY_FINGERPRINT_HEX)
    }

    /** Last [MASK_VISIBLE] alphanumerics of an account/IBAN, everything before them withheld. */
    fun mask(account: String): String {
        val compact = account.filter { it.isLetterOrDigit() }
        return if (compact.length <= MASK_VISIBLE) "****" else "…" + compact.takeLast(MASK_VISIBLE).uppercase()
    }

    /** A short, recognisable handle for an identifier: its first [HANDLE_CHARS] safe characters. */
    fun handle(id: String): String {
        val safe = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return if (safe.length <= HANDLE_CHARS) safe.ifEmpty { "-" } else safe.take(HANDLE_CHARS) + "…"
    }

    private fun hexPrefix(value: String): String {
        val sha256 = value.length == SHA256_HEX_LENGTH && HEX.matches(value)
        return if (sha256) value.take(KEY_FINGERPRINT_HEX).lowercase() else "invalid"
    }

    private fun shaped(value: String?, shape: Regex): String = when {
        value == null -> "-"
        shape.matches(value) -> value
        else -> "invalid"
    }
}
