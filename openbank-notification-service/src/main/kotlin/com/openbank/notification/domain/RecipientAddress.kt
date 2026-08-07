// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain

/**
 * Tells an e-mail address from something that is not one (issue #3581).
 *
 * Deliberately not a full RFC 5322 validator — the only judgement this makes is "may this string
 * be handed to the mailer as an envelope address, or must it be resolved first?", and the thing it
 * has to reject is a bare party UUID. A permissive shape check is right here: a stricter grammar
 * would start rejecting real addresses, and the fallback for a rejected string is a party-service
 * lookup, not a failure.
 */
object RecipientAddress {

    /** A UUID has no `@`, so this alone separates a party id from an address. */
    fun isEmailAddress(candidate: String?): Boolean {
        val value = candidate?.trim().orEmpty()
        if (value.isEmpty() || value.any { it.isWhitespace() }) return false
        val at = value.indexOf('@')
        if (at <= 0 || at != value.lastIndexOf('@')) return false
        val domain = value.substring(at + 1)
        return domain.length >= MIN_DOMAIN_LENGTH &&
            domain.contains('.') &&
            !domain.startsWith('.') &&
            !domain.endsWith('.')
    }

    /** `a.bc` — the shortest thing that can be a host with a TLD. */
    private const val MIN_DOMAIN_LENGTH = 4
}
