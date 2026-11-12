// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.usecase

import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ResolutionStep
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/** SHA-256 request fingerprint that binds an Idempotency-Key to one resolution request. */
internal object NostroBreakResolutionFingerprint {
    fun of(account: String, breakId: UUID, step: ResolutionStep, reason: String?, actor: Actor): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest("$account|$breakId|$step|${actor.id}|$reason".toByteArray()),
        )
}
