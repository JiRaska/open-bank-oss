// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.domain.model

import com.openbank.libs.domain.money.CurrencyCode
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * A currency component ("měnová složka") of a multi-currency account (ADR-0024). One account has a
 * single IBAN and one primary pocket plus N secondary pockets, each settling in its own currency.
 * The IBAN does not encode currency; the incoming payment's currency selects the pocket.
 *
 * Cover is evaluated per pocket — currencies are never netted. A pocket maps 1:1 to a balance-service
 * balance keyed by (accountId, currency); this aggregate owns the structure, not the running balance.
 */
data class CurrencyPocket(
    val id: UUID,
    val accountId: UUID,
    val currency: CurrencyCode,
    val isPrimary: Boolean,
    val status: PocketStatus,
    val openedAt: Instant,
    val closedAt: Instant?,
    val version: Long,
) {
    fun isOperable(): Boolean = status == PocketStatus.ACTIVE

    fun close(clock: Clock): CurrencyPocket {
        check(!isPrimary) { "Cannot close the primary pocket; close the account instead" }
        check(status != PocketStatus.CLOSED) { "Pocket is already closed" }
        return copy(status = PocketStatus.CLOSED, closedAt = Instant.now(clock), version = version + 1)
    }

    fun freeze(): CurrencyPocket {
        check(status == PocketStatus.ACTIVE) { "Cannot freeze pocket in status $status" }
        return copy(status = PocketStatus.FROZEN, version = version + 1)
    }

    fun unfreeze(): CurrencyPocket {
        check(status == PocketStatus.FROZEN) { "Cannot unfreeze pocket in status $status" }
        return copy(status = PocketStatus.ACTIVE, version = version + 1)
    }
}

enum class PocketStatus { ACTIVE, FROZEN, CLOSED }

/**
 * How to route an inbound payment whose currency has no matching pocket on the account (ADR-0024).
 *  - REJECT: bounce the payment (safest; explicit customer opt-in required for new currencies).
 *  - AUTO_CREATE: open a new pocket in the payment currency and credit it as-is (no FX).
 *  - CONVERT_TO_PRIMARY: convert the amount to the primary currency and credit the primary pocket.
 */
enum class MissingPocketPolicy { REJECT, AUTO_CREATE, CONVERT_TO_PRIMARY }
