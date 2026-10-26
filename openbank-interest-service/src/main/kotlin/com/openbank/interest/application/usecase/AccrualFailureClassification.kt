// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.interest.application.usecase

import com.openbank.interest.application.port.out.AccountSnapshot
import com.openbank.interest.domain.model.CatalogRevisionUnresolvableException
import com.openbank.interest.domain.model.RateConfigNotFoundException
import org.jboss.logging.Logger

internal enum class AccrualState { ACCRUED, SKIPPED, FAILED, UNAVAILABLE }

internal data class AccountAccrualResult(val state: AccrualState, val reason: String? = null)

/**
 * How one account's thrown accrual counts in the daily run. No rate for the product is a configuration
 * fact (SKIPPED). An unresolvable catalog pin is FAILED, not skipped: the account accrued nothing, so
 * the run must read incomplete and the ledger hold the day instead of closing it without that interest
 * (ADR-0341/0342); it keeps its WARN. The counter is incremented once, in `InterestService.accrue`, for
 * every caller. Anything else is FAILED.
 */
internal fun classifyAccrualFailure(account: AccountSnapshot, e: Throwable, log: Logger): AccountAccrualResult =
    when (e) {
        is RateConfigNotFoundException -> AccountAccrualResult(AccrualState.SKIPPED)
        is CatalogRevisionUnresolvableException -> {
            log.warnf("accrual failed for account %s: %s", account.id, e.message)
            AccountAccrualResult(
                AccrualState.FAILED,
                "catalog revision unresolvable for account ${account.id}: ${e.message}",
            )
        }
        else -> AccountAccrualResult(
            AccrualState.FAILED,
            "accrual failed for account ${account.id}: ${e.message ?: e.javaClass.simpleName}",
        )
    }
