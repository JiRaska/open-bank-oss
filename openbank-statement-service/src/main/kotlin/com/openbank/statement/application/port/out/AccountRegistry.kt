// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.application.port.out

import io.smallrye.mutiny.Uni
import java.util.UUID

/**
 * Read-only projection of the accounts that exist in the platform, built by consuming the
 * account-service `AccountCreated` stream. Backs the scheduled monthly period-close, which must
 * enumerate every account (account-service has no "all accounts" endpoint and owns its own DB).
 */
interface AccountRegistry {
    /** Idempotently record an account (no-op if already known). Safe under at-least-once redelivery. */
    fun upsertOpen(accountId: UUID, partyId: UUID, currency: String): Uni<Void>

    /** Every account known to the registry — the enumeration source for [monthly close]. */
    fun allAccountIds(): Uni<List<UUID>>
}
