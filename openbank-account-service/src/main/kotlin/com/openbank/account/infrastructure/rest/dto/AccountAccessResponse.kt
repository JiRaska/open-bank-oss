// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.rest.dto

import com.openbank.account.domain.model.AccountAccessEntry
import java.math.BigDecimal
import java.util.UUID

/**
 * Wire shape of one effective-access entry.
 *
 * Carries the party id and nothing else identifying: names, contact details and the grant's
 * free-text label stay out. The caller (the edge, then the app) resolves a display name from the
 * party the customer already knows, so this endpoint cannot become a way to turn an account id
 * into a person's details.
 */
data class AccountAccessResponse(
    val partyId: UUID,
    val source: String,
    val canInitiatePayments: Boolean,
    val capabilities: Set<String>,
    val perTransactionLimit: BigDecimal? = null,
    val perTransactionLimitCurrency: String? = null,
    val validFrom: String? = null,
    val validTo: String? = null,
    val grantId: UUID? = null,
) {
    companion object {
        fun from(entry: AccountAccessEntry) = AccountAccessResponse(
            partyId = entry.partyId,
            source = entry.source.name,
            canInitiatePayments = entry.canInitiatePayments,
            capabilities = entry.capabilities,
            perTransactionLimit = entry.perTransactionLimit,
            perTransactionLimitCurrency = entry.perTransactionLimitCurrency,
            validFrom = entry.validFrom?.toString(),
            validTo = entry.validTo?.toString(),
            grantId = entry.grantId,
        )
    }
}
