// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.kyb.infrastructure

import com.openbank.kyb.application.port.out.InvitationTokens
import jakarta.enterprise.context.ApplicationScoped
import java.security.SecureRandom
import java.util.Base64

/**
 * 192 bits from [SecureRandom], URL-safe base64 without padding (32 chars). An invitation token
 * is NOT a credential — claiming it still requires the claimant's own verified party — but it must
 * not be guessable, or a stranger could bind themselves to a company's case as a signer.
 */
@ApplicationScoped
class SecureInvitationTokens : InvitationTokens {
    private val random = SecureRandom()

    override fun next(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val TOKEN_BYTES = 24
    }
}
