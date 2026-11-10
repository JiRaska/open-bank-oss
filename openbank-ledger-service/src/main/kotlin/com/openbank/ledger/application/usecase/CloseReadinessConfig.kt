// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.application.usecase

/**
 * Required jobs resolved by BookCloseReadiness for this book. Property mappings live on
 * BankCloseReadiness and PensionCloseDeclarations, so a pension book cannot inherit bank jobs.
 */
interface CloseReadinessConfig {
    fun requiredJobs(): Map<String, Set<String>>
}
