// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.client

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.util.UUID

/**
 * Ledger-posting configuration for fee billing (ADR-0143 step 2): the system actor recorded as
 * the journal author (`postedBy`, ADR-0143 step 4).
 *
 * The two GL account UUIDs that used to live here (`billing.ledger.gl.fee-receivable` and
 * `billing.ledger.gl.fee-income`) are gone: legs name an accounting class and the ledger resolves
 * the account from its own map (ADR-0341 D3), so billing carries no chart-of-accounts knowledge.
 */
@ConfigMapping(prefix = "billing.ledger")
interface BillingLedgerConfig {

    /** The billing service's own system-actor id, recorded as the journal's `createdBy`. */
    @WithDefault("00000000-0000-0000-0000-0000000000bb")
    fun systemActorId(): UUID
}
