// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.client

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.util.UUID

/**
 * Ledger-posting configuration for the lending book: the system actor recorded as the journal author.
 *
 * The leaf GL accounts each posting kind debits/credits are no longer configuration — they are
 * platform-fixed seeded accounts held per-currency in [LendingGlChart] (issue #1275), mirroring how
 * `openbank-transaction-service`'s `PaymentJournalFactory` hardcodes its seeded accounts. The former
 * `@WithDefault` UUID placeholders were the footgun behind #1275/#1720: a missing or wrong mapping let
 * the service boot green and 422 at first posting instead of failing loud.
 */
@ConfigMapping(prefix = "lending.ledger")
interface LendingLedgerConfig {

    @WithDefault("00000000-0000-0000-0000-0000000000aa")
    fun systemActorId(): UUID
}
