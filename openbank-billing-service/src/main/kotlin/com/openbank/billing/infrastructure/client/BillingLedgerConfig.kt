// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.client

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.util.UUID

/**
 * Ledger-posting configuration for fee billing (ADR-0143 step 2): the system actor recorded as
 * the journal author (`postedBy`, ADR-0143 step 4) and the two leaf GL accounts every fee journal
 * debits/credits. Defaults are stable placeholder UUIDs (the `a0000000-…` family the ledger seeds,
 * same convention as `openbank-lending-service`'s `LendingLedgerConfig`); operators override them
 * to the real chart of accounts per environment.
 */
@ConfigMapping(prefix = "billing.ledger")
interface BillingLedgerConfig {

    /** The billing service's own system-actor id, recorded as the journal's `createdBy`. */
    @WithDefault("00000000-0000-0000-0000-0000000000bb")
    fun systemActorId(): UUID

    fun gl(): Gl

    interface Gl {
        /**
         * DEBIT — the customer fee-receivable GL (an asset; `subAccountId = accountId` ties it
         * to the sub-ledger, ADR-0039 Phase B).
         */
        @WithDefault("a0000000-0000-0000-0000-000000001400")
        fun feeReceivable(): UUID

        /** CREDIT — the bank's fee-income GL (matches the `4001 Fee Income` seed row, `V1__init_ledger.sql`). */
        @WithDefault("a0000000-0000-0000-0000-000000004001")
        fun feeIncome(): UUID
    }
}
